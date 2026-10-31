package in.munimsaathi.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Names;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.catalog.ProductRepository;
import in.munimsaathi.catalog.ProductUnit;
import in.munimsaathi.catalog.ProductUnitRepository;
import in.munimsaathi.draft.DraftBook;
import in.munimsaathi.draft.DraftLineSpec;
import in.munimsaathi.draft.DraftProposal;
import in.munimsaathi.evidence.Evidence;
import in.munimsaathi.evidence.EvidenceRepository;
import in.munimsaathi.interpretation.LanguageModel;
import in.munimsaathi.interpretation.Transcriber;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class VoiceReceipts {

    private static final int AUDIO_LIMIT = 15 * 1024 * 1024;
    private static final Set<String> AUDIO = Set.of("audio/ogg", "audio/mpeg", "audio/wav");

    private final TenantContext tenants;
    private final Transcriber transcriber;
    private final LanguageModel model;
    private final ObjectMapper mapper;
    private final PartyBook parties;
    private final CatalogService catalog;
    private final ProductRepository products;
    private final ProductUnitRepository units;
    private final GodownRepository godowns;
    private final DraftBook drafts;
    private final EvidenceRepository evidence;

    public VoiceReceipts(
            TenantContext tenants,
            Transcriber transcriber,
            LanguageModel model,
            ObjectMapper mapper,
            PartyBook parties,
            CatalogService catalog,
            ProductRepository products,
            ProductUnitRepository units,
            GodownRepository godowns,
            DraftBook drafts,
            EvidenceRepository evidence
    ) {
        this.tenants = tenants;
        this.transcriber = transcriber;
        this.model = model;
        this.mapper = mapper;
        this.parties = parties;
        this.catalog = catalog;
        this.products = products;
        this.units = units;
        this.godowns = godowns;
        this.drafts = drafts;
        this.evidence = evidence;
    }

    @Transactional
    public VoiceCapture capture(
            UUID userId,
            String mediaType,
            String fileName,
            byte[] audio,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if (!AUDIO.contains(type)) {
            throw new IllegalArgumentException("Attach a voice note.");
        }
        if (audio == null || audio.length == 0 || audio.length > AUDIO_LIMIT) {
            throw new IllegalArgumentException("The voice note is empty or larger than the upload limit.");
        }
        String transcript = transcriber.transcribe(audio, type);
        if (transcript == null || transcript.isBlank()) {
            throw new IllegalArgumentException("The voice note had no transcript.");
        }
        JsonNode reply;
        try {
            reply = mapper.readTree(model.complete(transcript));
        } catch (Exception exception) {
            throw new IllegalArgumentException("The model reply was not readable.", exception);
        }
        UUID customerId = party(userId, text(reply, "party_name"));
        UUID locationId = location(userId, text(reply, "location_code"));
        String action = "sale".equalsIgnoreCase(text(reply, "intent")) ? "sale" : null;
        List<DraftLineSpec> lines = lines(userId, reply.get("lines"));
        DraftProposal draft = drafts.proposeLines(userId, action, customerId, locationId, lines, occurredOn, occurredAt);
        UUID businessId = tenants.currentBusinessId(userId);
        Evidence stored = evidence.save(new Evidence(
                UUID.randomUUID(),
                businessId,
                draft.id(),
                type,
                fileName,
                sha256(audio),
                audio,
                Instant.now()
        ));
        return new VoiceCapture(draft, stored.getId(), transcript);
    }

    private List<DraftLineSpec> lines(UUID userId, JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException("A voice receipt needs every line, including unresolved ones.");
        }
        List<DraftLineSpec> lines = new ArrayList<>();
        for (JsonNode node : array) {
            UUID productId = product(userId, text(node, "product_name"));
            BigDecimal quantity = quantity(node.get("quantity"));
            String unit = text(node, "unit");
            Long amount = amount(node.get("amount_paise"));
            String unresolved = null;
            if (productId == null) {
                unresolved = "product";
            } else if (quantity == null) {
                unresolved = "quantity";
            } else if (unit == null) {
                unresolved = "unit";
            } else if (!convertible(productId, quantity, unit)) {
                unresolved = "unit";
            }
            lines.add(new DraftLineSpec(productId, quantity, unit, amount, unresolved));
        }
        return lines;
    }

    private boolean convertible(UUID productId, BigDecimal quantity, String unit) {
        ProductUnit known = units.findByProductIdAndUnitLabel(productId, Names.normalize(unit)).orElse(null);
        if (known == null) {
            return false;
        }
        BigDecimal base = quantity.multiply(known.getFactorToBase());
        Product product = products.findById(productId).orElseThrow();
        return product.isBaseUnitDivisible() || base.stripTrailingZeros().scale() <= 0;
    }

    private UUID party(UUID userId, String name) {
        if (name == null) {
            return null;
        }
        List<Party> matches = parties.findByName(userId, name).stream()
                .filter(party -> party.getRole() == PartyRole.CUSTOMER || party.getRole() == PartyRole.BOTH)
                .toList();
        return matches.size() == 1 ? matches.get(0).getId() : null;
    }

    private UUID product(UUID userId, String name) {
        if (name == null) {
            return null;
        }
        var approved = catalog.resolveApproved(userId, name);
        if (approved.isPresent()) {
            return approved.get().getId();
        }
        List<Product> matches = catalog.candidates(userId, name);
        return matches.size() == 1 ? matches.get(0).getId() : null;
    }

    private UUID location(UUID userId, String code) {
        if (code == null) {
            return null;
        }
        UUID businessId = tenants.currentBusinessId(userId);
        List<Godown> matches = godowns.findByBusinessIdOrderByCodeAsc(businessId).stream()
                .filter(godown -> godown.getCode().equalsIgnoreCase(code) || godown.getName().equalsIgnoreCase(code))
                .toList();
        return matches.size() == 1 ? matches.get(0).getId() : null;
    }

    private static BigDecimal quantity(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.isNumber() ? node.decimalValue().toPlainString() : node.asText();
        if (raw == null || !raw.matches("-?\\d+(\\.\\d+)?")) {
            return null;
        }
        return new BigDecimal(raw);
    }

    private static Long amount(JsonNode node) {
        if (node == null || node.isNull() || !node.isIntegralNumber()) {
            return null;
        }
        return node.longValue();
    }

    private static String text(JsonNode reply, String field) {
        JsonNode node = reply.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() || "null".equals(value) ? null : value.trim();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    public record VoiceCapture(DraftProposal draft, UUID evidenceId, String transcript) {
    }
}
