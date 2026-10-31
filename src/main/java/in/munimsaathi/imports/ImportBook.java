package in.munimsaathi.imports;

import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.inventory.CountService;
import in.munimsaathi.inventory.PostedOperationRepository;
import in.munimsaathi.ledger.PostingService;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ImportBook {

    private static final String HEADER = "row_class,source_id,name,quantity,amount_paise";

    private final TenantContext tenants;
    private final ImportBatchRepository batches;
    private final ImportRowRepository rows;
    private final CatalogService catalog;
    private final PartyBook parties;
    private final CountService counts;
    private final PostedOperationRepository operations;
    private final PostingService posting;

    public ImportBook(
            TenantContext tenants,
            ImportBatchRepository batches,
            ImportRowRepository rows,
            CatalogService catalog,
            PartyBook parties,
            CountService counts,
            PostedOperationRepository operations,
            PostingService posting
    ) {
        this.tenants = tenants;
        this.batches = batches;
        this.rows = rows;
        this.catalog = catalog;
        this.parties = parties;
        this.counts = counts;
        this.operations = operations;
        this.posting = posting;
    }

    @Transactional
    public UUID stage(UUID userId, String fileName, byte[] csv) {
        UUID businessId = tenants.currentBusinessId(userId);
        String hash = sha256(csv);
        var existing = batches.findByBusinessIdAndFileHash(businessId, hash);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        List<Parsed> parsed = parse(csv);
        List<ImportRow> staged = new ArrayList<>();
        UUID importId = UUID.randomUUID();
        BigDecimal openingPieces = BigDecimal.ZERO;
        long openingPaise = 0;
        int historical = 0;
        int quarantined = 0;
        int number = 0;
        for (Parsed row : parsed) {
            number = number + 1;
            UUID productId = product(userId, row.name());
            UUID partyId = party(userId, row.name());
            ImportRowStatus status = ImportRowStatus.MAPPED;
            String error = null;
            if (row.sourceId() == null || row.sourceId().isBlank()) {
                status = ImportRowStatus.QUARANTINED;
                error = "source id";
            } else if (!known(row.rowClass())) {
                status = ImportRowStatus.QUARANTINED;
                error = "unsupported row";
            } else if ("opening_stock".equals(row.rowClass()) && productId == null) {
                status = ImportRowStatus.QUARANTINED;
                error = "unmapped product";
            } else if ("opening_receivable".equals(row.rowClass()) && partyId == null) {
                status = ImportRowStatus.QUARANTINED;
                error = "unmapped party";
            } else if ("sale".equals(row.rowClass())) {
                status = ImportRowStatus.HISTORICAL;
                historical = historical + 1;
            }
            if (status == ImportRowStatus.QUARANTINED) {
                quarantined = quarantined + 1;
            }
            if (status == ImportRowStatus.MAPPED && "opening_stock".equals(row.rowClass()) && row.quantity() != null) {
                openingPieces = openingPieces.add(row.quantity());
            }
            if (status == ImportRowStatus.MAPPED && "opening_receivable".equals(row.rowClass()) && row.amountPaise() != null) {
                openingPaise = Math.addExact(openingPaise, row.amountPaise());
            }
            staged.add(new ImportRow(
                    UUID.randomUUID(),
                    importId,
                    number,
                    row.rowClass(),
                    blank(row.sourceId()),
                    blank(row.name()),
                    row.quantity(),
                    row.amountPaise(),
                    productId,
                    partyId,
                    status,
                    error
            ));
        }
        batches.save(new ImportBatch(
                importId,
                businessId,
                fileName,
                hash,
                openingPieces,
                openingPaise,
                historical,
                quarantined
        ));
        rows.saveAll(staged);
        return importId;
    }

    @Transactional
    public void activate(UUID userId, UUID importId, UUID godownId, LocalDate day, Instant at) {
        UUID businessId = tenants.currentBusinessId(userId);
        ImportBatch batch = batches.findById(importId).orElseThrow();
        if (!batch.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("This import is not in the selected business.");
        }
        if (batch.getStatus() == ImportStatus.ACTIVE) {
            return;
        }
        if (batch.getQuarantinedRows() > 0) {
            throw new IllegalArgumentException("Unresolved rows stay quarantined.");
        }
        UUID stockOperation = UUID.nameUUIDFromBytes((importId + ":stock").getBytes(StandardCharsets.UTF_8));
        if (!operations.existsById(stockOperation)) {
            List<ImportRow> opening = rows.findByImportIdOrderByRowNumberAsc(importId).stream()
                    .filter(row -> "opening_stock".equals(row.getRowClass()))
                    .toList();
            if (!opening.isEmpty()) {
                UUID sessionId = counts.start(userId, godownId, at, "import");
                for (ImportRow row : opening) {
                    counts.propose(userId, sessionId, row.getProductId(), row.getQuantity(), "piece", null);
                }
                counts.confirm(userId, sessionId, stockOperation);
            }
        }
        for (ImportRow row : rows.findByImportIdOrderByRowNumberAsc(importId)) {
            if (!"opening_receivable".equals(row.getRowClass()) || row.getAmountPaise() == null) {
                continue;
            }
            UUID moneyOperation = UUID.nameUUIDFromBytes(
                    (importId + ":receivable:" + row.getSourceId()).getBytes(StandardCharsets.UTF_8)
            );
            posting.recordOpeningReceivable(userId, moneyOperation, row.getPartyId(), row.getAmountPaise(), day, at);
        }
        batch.activate();
    }

    private UUID product(UUID userId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        List<Product> matches = catalog.candidates(userId, name);
        return matches.size() == 1 ? matches.get(0).getId() : null;
    }

    private UUID party(UUID userId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        List<Party> matches = parties.findByName(userId, name).stream()
                .filter(party -> party.getRole() == PartyRole.CUSTOMER || party.getRole() == PartyRole.BOTH)
                .toList();
        return matches.size() == 1 ? matches.get(0).getId() : null;
    }

    private static boolean known(String rowClass) {
        return "product".equals(rowClass)
                || "party".equals(rowClass)
                || "opening_stock".equals(rowClass)
                || "opening_receivable".equals(rowClass)
                || "sale".equals(rowClass);
    }

    private static List<Parsed> parse(byte[] csv) {
        String text = new String(csv, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n");
        if (lines.length == 0 || !HEADER.equals(lines[0].trim())) {
            throw new IllegalArgumentException("Use the supported export header.");
        }
        List<Parsed> parsed = new ArrayList<>();
        for (int index = 1; index < lines.length; index = index + 1) {
            if (lines[index].isBlank()) {
                continue;
            }
            String[] cells = lines[index].split(",", -1);
            parsed.add(new Parsed(
                    cell(cells, 0),
                    cell(cells, 1),
                    cell(cells, 2),
                    decimal(cell(cells, 3)),
                    integer(cell(cells, 4))
            ));
        }
        return parsed;
    }

    private static String cell(String[] cells, int index) {
        if (index >= cells.length) {
            return null;
        }
        String value = cells[index].trim();
        return value.isEmpty() ? null : value;
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Long integer(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sha256(byte[] csv) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record Parsed(String rowClass, String sourceId, String name, BigDecimal quantity, Long amountPaise) {
    }
}
