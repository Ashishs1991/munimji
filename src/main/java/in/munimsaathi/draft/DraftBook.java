package in.munimsaathi.draft;

import in.munimsaathi.ledger.PostingService;
import in.munimsaathi.ledger.PostedDocumentRepository;
import in.munimsaathi.ledger.SaleLine;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class DraftBook {

    private static final String SALE = "SALE";

    private final TenantContext tenants;
    private final DraftRepository drafts;
    private final DraftLineRepository draftLines;
    private final ApprovalRepository approvals;
    private final PostingService posting;
    private final PostedDocumentRepository documents;

    public DraftBook(
            TenantContext tenants,
            DraftRepository drafts,
            DraftLineRepository draftLines,
            ApprovalRepository approvals,
            PostingService posting,
            PostedDocumentRepository documents
    ) {
        this.tenants = tenants;
        this.drafts = drafts;
        this.draftLines = draftLines;
        this.approvals = approvals;
        this.posting = posting;
        this.documents = documents;
    }

    @Transactional
    public DraftProposal propose(
            UUID userId,
            String action,
            UUID customerId,
            UUID productId,
            UUID locationId,
            BigDecimal quantity,
            String unit,
            Long amountPaise,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Draft draft = new Draft(UUID.randomUUID(), businessId, userId);
        fill(draft, action, customerId, productId, locationId, quantity, unit, amountPaise, occurredOn, occurredAt);
        return view(drafts.save(draft));
    }

    @Transactional
    public DraftProposal proposeLines(
            UUID userId,
            String action,
            UUID customerId,
            UUID locationId,
            List<DraftLineSpec> lines,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A voice receipt needs every line, including unresolved ones.");
        }
        UUID businessId = tenants.currentBusinessId(userId);
        Draft draft = new Draft(UUID.randomUUID(), businessId, userId);
        String kind = actionKind(action);
        List<String> missing = new ArrayList<>();
        if (kind == null) {
            missing.add("action");
        }
        if (occurredOn == null || occurredAt == null) {
            missing.add("date");
        }
        if (locationId == null) {
            missing.add("location");
        }
        if (customerId == null) {
            missing.add("party");
        }
        StringBuilder lineCanon = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            DraftLineSpec line = lines.get(index);
            if (line.unresolved() != null) {
                missing.add("line-" + (index + 1));
            }
            lineCanon.append(index + 1)
                    .append("|").append(text(line.productId()))
                    .append("|").append(line.displayQuantity() == null ? "" : line.displayQuantity().stripTrailingZeros().toPlainString())
                    .append("|").append(text(line.unitLabel()))
                    .append("|").append(line.amountPaise() == null ? "" : line.amountPaise())
                    .append("|").append(text(line.unresolved()))
                    .append(";");
        }
        long nextVersion = draft.getVersion() + 1;
        String hash = hash(nextVersion, kind, customerId, null, locationId, null, null, null, occurredOn, occurredAt, lineCanon.toString());
        draft.replace(
                kind,
                missing.isEmpty() ? DraftStatus.AWAITING_APPROVAL : DraftStatus.NEEDS_CLARIFICATION,
                hash,
                customerId,
                null,
                locationId,
                null,
                null,
                null,
                occurredOn,
                occurredAt,
                String.join(",", missing),
                Instant.now().plus(2, ChronoUnit.HOURS)
        );
        drafts.save(draft);
        for (int index = 0; index < lines.size(); index++) {
            DraftLineSpec line = lines.get(index);
            draftLines.save(new DraftLine(
                    UUID.randomUUID(),
                    draft.getId(),
                    index + 1,
                    line.productId(),
                    line.displayQuantity(),
                    line.unitLabel(),
                    line.amountPaise(),
                    line.unresolved()
            ));
        }
        return view(draft);
    }

    @Transactional
    public DraftProposal edit(
            UUID userId,
            UUID draftId,
            String action,
            UUID customerId,
            UUID productId,
            UUID locationId,
            BigDecimal quantity,
            String unit,
            Long amountPaise,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        Draft draft = requireDraft(tenants.currentBusinessId(userId), draftId);
        if (draft.getStatus() == DraftStatus.POSTED) {
            throw new IllegalArgumentException("A posted proposal cannot be edited.");
        }
        fill(draft, action, customerId, productId, locationId, quantity, unit, amountPaise, occurredOn, occurredAt);
        return view(draft);
    }

    @Transactional(readOnly = true)
    public DraftProposal get(UUID userId, UUID draftId) {
        return view(requireDraft(tenants.currentBusinessId(userId), draftId));
    }

    @Transactional
    public UUID approve(UUID userId, UUID draftId, long version, String contentHash) {
        UUID businessId = tenants.currentBusinessId(userId);
        Draft draft = requireDraft(businessId, draftId);
        if (draft.getStatus() == DraftStatus.POSTED) {
            if (draft.getVersion() == version && draft.getContentHash().equals(contentHash)) {
                return documents.findByOperationId(draft.getId()).orElseThrow().getId();
            }
            throw new IllegalArgumentException("This approval is no longer current.");
        }
        if (draft.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("This approval has expired.");
        }
        if (draft.getStatus() != DraftStatus.AWAITING_APPROVAL
                || draft.getVersion() != version
                || !draft.getContentHash().equals(contentHash)) {
            throw new IllegalArgumentException("This approval is no longer current.");
        }
        approvals.save(new Approval(
                UUID.randomUUID(),
                draft.getId(),
                draft.getVersion(),
                draft.getContentHash(),
                userId,
                Instant.now()
        ));
        UUID documentId = post(userId, draft);
        draft.markPosted();
        return documentId;
    }

    @Transactional
    public UUID approveCurrent(UUID userId) {
        UUID businessId = tenants.currentBusinessId(userId);
        List<Draft> pending = drafts.findByBusinessIdAndStatus(businessId, DraftStatus.AWAITING_APPROVAL).stream()
                .filter(draft -> !draft.getExpiresAt().isBefore(Instant.now()))
                .toList();
        if (pending.size() != 1) {
            throw new IllegalArgumentException("Yes approves only one current proposal.");
        }
        Draft draft = pending.get(0);
        return approve(userId, draft.getId(), draft.getVersion(), draft.getContentHash());
    }

    private UUID post(UUID userId, Draft draft) {
        List<DraftLine> rows = draftLines.findByDraftIdOrderByLineNumberAsc(draft.getId());
        if (!rows.isEmpty()) {
            List<SaleLine> saleLines = new ArrayList<>();
            for (DraftLine row : rows) {
                if (row.getUnresolved() != null || row.getProductId() == null
                        || row.getDisplayQuantity() == null || row.getUnitLabel() == null) {
                    throw new IllegalArgumentException("This approval is no longer current.");
                }
                saleLines.add(new SaleLine(
                        row.getProductId(), row.getDisplayQuantity(), row.getUnitLabel(), row.getAmountPaise()
                ));
            }
            return posting.sellLines(
                    userId,
                    draft.getId(),
                    draft.getCustomerId(),
                    draft.getLocationId(),
                    saleLines,
                    draft.getOccurredOn(),
                    draft.getOccurredAt()
            );
        }
        if (draft.getAmountPaise() == null) {
            return posting.dispatch(
                    userId,
                    draft.getId(),
                    draft.getCustomerId(),
                    draft.getProductId(),
                    draft.getLocationId(),
                    draft.getDisplayQuantity(),
                    draft.getUnitLabel(),
                    draft.getOccurredOn(),
                    draft.getOccurredAt()
            );
        }
        return posting.sell(
                userId,
                draft.getId(),
                draft.getCustomerId(),
                draft.getProductId(),
                draft.getLocationId(),
                draft.getDisplayQuantity(),
                draft.getUnitLabel(),
                draft.getAmountPaise(),
                draft.getOccurredOn(),
                draft.getOccurredAt()
        );
    }

    private void fill(
            Draft draft,
            String action,
            UUID customerId,
            UUID productId,
            UUID locationId,
            BigDecimal quantity,
            String unit,
            Long amountPaise,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        if (quantity != null && quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero.");
        }
        if (amountPaise != null && amountPaise <= 0) {
            throw new IllegalArgumentException("A price must be greater than zero.");
        }
        String kind = actionKind(action);
        String unitLabel = unit == null || unit.isBlank() ? null : unit.trim();
        List<String> missing = missing(kind, customerId, productId, locationId, quantity, unitLabel, amountPaise, occurredOn, occurredAt);
        boolean ready = missing.stream().allMatch("price"::equals);
        long nextVersion = draft.getVersion() + 1;
        String hash = hash(nextVersion, kind, customerId, productId, locationId, quantity, unitLabel, amountPaise, occurredOn, occurredAt, "");
        draft.replace(
                kind,
                ready ? DraftStatus.AWAITING_APPROVAL : DraftStatus.NEEDS_CLARIFICATION,
                hash,
                customerId,
                productId,
                locationId,
                quantity,
                unitLabel,
                amountPaise,
                occurredOn,
                occurredAt,
                String.join(",", missing),
                Instant.now().plus(2, ChronoUnit.HOURS)
        );
    }

    private Draft requireDraft(UUID businessId, UUID draftId) {
        Draft draft = drafts.findById(draftId).orElseThrow(() -> new IllegalArgumentException("Unknown proposal."));
        if (!draft.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown proposal.");
        }
        return draft;
    }

    private static String actionKind(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        if (!SALE.equalsIgnoreCase(action.trim())) {
            throw new IllegalArgumentException("Unknown action.");
        }
        return SALE;
    }

    private static List<String> missing(
            String kind,
            UUID customerId,
            UUID productId,
            UUID locationId,
            BigDecimal quantity,
            String unit,
            Long amountPaise,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        List<String> missing = new ArrayList<>();
        if (kind == null) {
            missing.add("action");
        }
        if (occurredOn == null || occurredAt == null) {
            missing.add("date");
        }
        if (locationId == null) {
            missing.add("location");
        }
        if (customerId == null) {
            missing.add("party");
        }
        if (amountPaise == null) {
            missing.add("price");
        }
        if (productId == null) {
            missing.add("product");
        }
        if (quantity == null) {
            missing.add("quantity");
        }
        if (unit == null) {
            missing.add("unit");
        }
        return missing;
    }

    private static String hash(
            long version,
            String kind,
            UUID customerId,
            UUID productId,
            UUID locationId,
            BigDecimal quantity,
            String unit,
            Long amountPaise,
            LocalDate occurredOn,
            Instant occurredAt,
            String extra
    ) {
        String canonical = version
                + "|" + text(kind)
                + "|" + text(customerId)
                + "|" + text(productId)
                + "|" + text(locationId)
                + "|" + (quantity == null ? "" : quantity.stripTrailingZeros().toPlainString())
                + "|" + text(unit)
                + "|" + (amountPaise == null ? "" : amountPaise)
                + "|" + text(occurredOn)
                + "|" + text(occurredAt)
                + "|" + text(extra);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static DraftProposal view(Draft draft) {
        List<String> missing = draft.getMissingFields().isBlank()
                ? List.of()
                : List.of(draft.getMissingFields().split(","));
        return new DraftProposal(
                draft.getId(),
                draft.getVersion(),
                draft.getContentHash(),
                draft.getStatus(),
                missing,
                draft.getAmountPaise()
        );
    }
}
