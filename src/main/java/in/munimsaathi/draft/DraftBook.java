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
        if (draftLines.findByDraftIdOrderByLineNumberAsc(draft.getId()).size() > 1) {
            throw new IllegalArgumentException("Which line should change?");
        }
        fill(draft, action, customerId, productId, locationId, quantity, unit, amountPaise, occurredOn, occurredAt);
        rewriteLines(draft);
        return view(draft);
    }

    @Transactional
    public DraftProposal continueDraft(
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
        List<DraftLine> existing = draftLines.findByDraftIdOrderByLineNumberAsc(draft.getId());
        boolean lineChange = productId != null || quantity != null || unit != null || amountPaise != null;
        if (existing.size() > 1) {
            if (lineChange) {
                List<DraftLine> matches = existing.stream()
                        .filter(line -> productId != null && productId.equals(line.getProductId()))
                        .toList();
                if (matches.size() != 1) {
                    throw new IllegalArgumentException("Which line should change?");
                }
                DraftLine line = matches.get(0);
                BigDecimal nextQuantity = quantity != null ? quantity : line.getDisplayQuantity();
                String nextUnit = unit != null ? unit : line.getUnitLabel();
                Long nextAmount = amountPaise != null ? amountPaise : line.getAmountPaise();
                String unresolved = nextQuantity == null ? "quantity" : nextUnit == null ? "unit" : null;
                line.replace(line.getProductId(), nextQuantity, nextUnit, nextAmount, unresolved);
            }
            keepLines(draft, action, customerId, locationId, occurredOn, occurredAt);
            return view(draft);
        }
        if (!existing.isEmpty()) {
            if (lineChange && existing.size() == 1) {
                DraftLine line = existing.get(0);
                UUID nextProduct = productId != null ? productId : line.getProductId();
                BigDecimal nextQuantity = quantity != null ? quantity : line.getDisplayQuantity();
                String nextUnit = unit != null ? unit : line.getUnitLabel();
                Long nextAmount = amountPaise != null ? amountPaise : line.getAmountPaise();
                String unresolved = nextProduct == null ? "product" : nextQuantity == null ? "quantity" : nextUnit == null ? "unit" : null;
                line.replace(nextProduct, nextQuantity, nextUnit, nextAmount, unresolved);
            }
            keepLines(draft, action, customerId, locationId, occurredOn, occurredAt);
            return view(draft);
        }
        fill(
                draft,
                action != null ? action : draft.getKind(),
                customerId != null ? customerId : draft.getCustomerId(),
                productId != null ? productId : draft.getProductId(),
                locationId != null ? locationId : draft.getLocationId(),
                quantity != null ? quantity : draft.getDisplayQuantity(),
                unit != null ? unit : draft.getUnitLabel(),
                amountPaise != null ? amountPaise : draft.getAmountPaise(),
                draft.getOccurredOn() != null ? draft.getOccurredOn() : occurredOn,
                draft.getOccurredAt() != null ? draft.getOccurredAt() : occurredAt
        );
        return view(draft);
    }

    private void keepLines(
            Draft draft,
            String action,
            UUID customerId,
            UUID locationId,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        String kind = action != null ? actionKind(action) : draft.getKind();
        UUID customer = customerId != null ? customerId : draft.getCustomerId();
        UUID location = locationId != null ? locationId : draft.getLocationId();
        LocalDate day = draft.getOccurredOn() != null ? draft.getOccurredOn() : occurredOn;
        Instant at = draft.getOccurredAt() != null ? draft.getOccurredAt() : occurredAt;
        List<DraftLine> lines = draftLines.findByDraftIdOrderByLineNumberAsc(draft.getId());
        List<String> missing = new ArrayList<>();
        if (kind == null) {
            missing.add("action");
        }
        if (day == null || at == null) {
            missing.add("date");
        }
        if (location == null) {
            missing.add("location");
        }
        if (customer == null) {
            missing.add("party");
        }
        StringBuilder canon = new StringBuilder();
        for (DraftLine line : lines) {
            if (line.getUnresolved() != null) {
                missing.add("line-" + line.getLineNumber());
            }
            canon.append(line.getLineNumber())
                    .append("|").append(text(line.getProductId()))
                    .append("|").append(line.getDisplayQuantity() == null ? "" : line.getDisplayQuantity().stripTrailingZeros().toPlainString())
                    .append("|").append(text(line.getUnitLabel()))
                    .append("|").append(line.getAmountPaise() == null ? "" : line.getAmountPaise())
                    .append("|").append(text(line.getUnresolved()))
                    .append(";");
        }
        long nextVersion = draft.getVersion() + 1;
        draft.replace(
                kind,
                missing.isEmpty() ? DraftStatus.AWAITING_APPROVAL : DraftStatus.NEEDS_CLARIFICATION,
                hash(nextVersion, kind, customer, null, location, null, null, null, day, at, canon.toString()),
                customer,
                null,
                location,
                null,
                null,
                null,
                day,
                at,
                String.join(",", missing),
                Instant.now().plus(2, ChronoUnit.HOURS)
        );
    }

    @Transactional
    public DraftProposal replaceLines(
            UUID userId,
            UUID draftId,
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
        Draft draft = requireDraft(tenants.currentBusinessId(userId), draftId);
        if (draft.getStatus() == DraftStatus.POSTED) {
            throw new IllegalArgumentException("A posted proposal cannot be edited.");
        }
        List<DraftLine> existing = draftLines.findByDraftIdOrderByLineNumberAsc(draft.getId());
        if (!existing.isEmpty()) {
            draftLines.deleteAll(existing);
            draftLines.flush();
        }
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
        keepLines(draft, action, customerId, locationId, occurredOn, occurredAt);
        return view(draft);
    }

    @Transactional(readOnly = true)
    public DraftProposal get(UUID userId, UUID draftId) {
        return view(requireDraft(tenants.currentBusinessId(userId), draftId));
    }

    @Transactional(readOnly = true)
    public String kindOf(UUID userId, UUID draftId) {
        return requireDraft(tenants.currentBusinessId(userId), draftId).getKind();
    }

    @Transactional(readOnly = true)
    public List<OpenDraft> open(UUID userId) {
        UUID businessId = tenants.currentBusinessId(userId);
        return drafts.findByBusinessIdAndStatusIn(
                businessId,
                List.of(DraftStatus.NEEDS_CLARIFICATION, DraftStatus.AWAITING_APPROVAL)
        ).stream()
                .filter(draft -> !draft.getExpiresAt().isBefore(Instant.now()))
                .map(draft -> new OpenDraft(
                        draft.getId(),
                        draft.getKind(),
                        draft.getMissingFields() == null ? "" : draft.getMissingFields(),
                        draft.getVersion(),
                        draft.getAmountPaise()
                ))
                .toList();
    }

    @Transactional
    public DraftProposal proposeMoney(
            UUID userId,
            String action,
            UUID partyId,
            Long amountPaise,
            String paymentMethod,
            UUID allocatesDocumentId,
            String cause,
            String direction,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        UUID businessId = tenants.currentBusinessId(userId);
        Draft draft = new Draft(UUID.randomUUID(), businessId, userId);
        DraftProposal proposal = writeMoney(draft, action, partyId, amountPaise, paymentMethod, allocatesDocumentId, cause, direction, occurredOn, occurredAt);
        drafts.save(draft);
        return proposal;
    }

    @Transactional
    public DraftProposal continueMoney(
            UUID userId,
            UUID draftId,
            String action,
            UUID partyId,
            Long amountPaise,
            String paymentMethod,
            UUID allocatesDocumentId,
            String cause,
            String direction,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        Draft draft = requireDraft(tenants.currentBusinessId(userId), draftId);
        if (draft.getStatus() == DraftStatus.POSTED) {
            throw new IllegalArgumentException("A posted proposal cannot be edited.");
        }
        return writeMoney(
                draft,
                action != null ? action : draft.getKind(),
                partyId != null ? partyId : draft.getCustomerId(),
                amountPaise != null ? amountPaise : draft.getAmountPaise(),
                paymentMethod != null ? paymentMethod : draft.getPaymentMethod(),
                allocatesDocumentId != null ? allocatesDocumentId : draft.getAllocatesDocumentId(),
                cause != null ? cause : draft.getCause(),
                direction != null ? direction : draft.getDirection(),
                draft.getOccurredOn() != null ? draft.getOccurredOn() : occurredOn,
                draft.getOccurredAt() != null ? draft.getOccurredAt() : occurredAt
        );
    }

    @Transactional
    public void suspend(UUID userId, UUID draftId) {
        Draft draft = requireDraft(tenants.currentBusinessId(userId), draftId);
        if (draft.getStatus() == DraftStatus.NEEDS_CLARIFICATION || draft.getStatus() == DraftStatus.AWAITING_APPROVAL) {
            draft.suspend();
        }
    }

    @Transactional
    public DraftProposal resume(UUID userId, UUID draftId) {
        Draft draft = requireDraft(tenants.currentBusinessId(userId), draftId);
        if (draft.getStatus() != DraftStatus.SUSPENDED) {
            throw new IllegalArgumentException("There is no suspended draft to resume.");
        }
        boolean ready = draft.getMissingFields() == null || draft.getMissingFields().isBlank();
        draft.resume(ready ? DraftStatus.AWAITING_APPROVAL : DraftStatus.NEEDS_CLARIFICATION);
        return view(draft);
    }

    @Transactional
    public void cancel(UUID userId, UUID draftId) {
        Draft draft = requireDraft(tenants.currentBusinessId(userId), draftId);
        if (draft.getStatus() != DraftStatus.POSTED) {
            draft.cancel();
        }
    }

    public record OpenDraft(UUID id, String kind, String missing, long version, Long amountPaise) {
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
        if ("PAYMENT".equals(draft.getKind())) {
            if (draft.getAllocatesDocumentId() != null) {
                return posting.receivePayment(
                        userId,
                        draft.getId(),
                        draft.getCustomerId(),
                        draft.getAllocatesDocumentId(),
                        draft.getAmountPaise(),
                        draft.getPaymentMethod(),
                        draft.getOccurredOn(),
                        draft.getOccurredAt()
                );
            }
            return posting.receiveUnallocated(
                    userId,
                    draft.getId(),
                    draft.getCustomerId(),
                    draft.getAmountPaise(),
                    draft.getPaymentMethod(),
                    draft.getOccurredOn(),
                    draft.getOccurredAt()
            );
        }
        if ("RECEIVABLE".equals(draft.getKind())) {
            return posting.recordStandaloneReceivable(
                    userId,
                    draft.getId(),
                    draft.getCustomerId(),
                    draft.getAmountPaise(),
                    draft.getCause(),
                    draft.getOccurredOn(),
                    draft.getOccurredAt()
            );
        }
        if ("PAYABLE".equals(draft.getKind())) {
            return posting.recordSupplierObligation(
                    userId,
                    draft.getId(),
                    draft.getCustomerId(),
                    draft.getAmountPaise(),
                    draft.getCause(),
                    draft.getOccurredOn(),
                    draft.getOccurredAt()
            );
        }
        List<DraftLine> rows = draftLines.findByDraftIdOrderByLineNumberAsc(draft.getId());
        if ("RECEIPT".equals(draft.getKind())) {
            return receive(userId, draft, rows);
        }
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

    private UUID receive(UUID userId, Draft draft, List<DraftLine> rows) {
        if (rows.isEmpty()) {
            return posting.receiveGoods(
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
        UUID first = null;
        for (DraftLine row : rows) {
            UUID operation = row.getLineNumber() == 1
                    ? draft.getId()
                    : UUID.nameUUIDFromBytes((draft.getId() + ":receipt:" + row.getLineNumber()).getBytes(StandardCharsets.UTF_8));
            UUID documentId = posting.receiveGoods(
                    userId,
                    operation,
                    draft.getCustomerId(),
                    row.getProductId(),
                    draft.getLocationId(),
                    row.getDisplayQuantity(),
                    row.getUnitLabel(),
                    draft.getOccurredOn(),
                    draft.getOccurredAt()
            );
            if (first == null) {
                first = documentId;
            }
        }
        return first;
    }

    private void rewriteLines(Draft draft) {
        List<DraftLine> existing = draftLines.findByDraftIdOrderByLineNumberAsc(draft.getId());
        if (existing.isEmpty()) {
            return;
        }
        draftLines.deleteAll(existing);
        draftLines.flush();
        draftLines.save(new DraftLine(
                UUID.randomUUID(),
                draft.getId(),
                1,
                draft.getProductId(),
                draft.getDisplayQuantity(),
                draft.getUnitLabel(),
                draft.getAmountPaise(),
                null
        ));
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
        String normalized = action.trim();
        if (SALE.equalsIgnoreCase(normalized)) {
            return SALE;
        }
        if ("receive".equalsIgnoreCase(normalized) || "purchase".equalsIgnoreCase(normalized) || "RECEIPT".equalsIgnoreCase(normalized) || "goods_received".equalsIgnoreCase(normalized)) {
            return "RECEIPT";
        }
        if ("payment".equalsIgnoreCase(normalized) || "PAYMENT".equalsIgnoreCase(normalized) || "customer_payment_received".equalsIgnoreCase(normalized)) {
            return "PAYMENT";
        }
        if ("receivable".equalsIgnoreCase(normalized) || "RECEIVABLE".equalsIgnoreCase(normalized) || "standalone_receivable".equalsIgnoreCase(normalized)) {
            return "RECEIVABLE";
        }
        if ("payable".equalsIgnoreCase(normalized) || "PAYABLE".equalsIgnoreCase(normalized) || "supplier_payable".equalsIgnoreCase(normalized)) {
            return "PAYABLE";
        }
        throw new IllegalArgumentException("Unknown action.");
    }

    private DraftProposal writeMoney(
            Draft draft,
            String action,
            UUID partyId,
            Long amountPaise,
            String paymentMethod,
            UUID allocatesDocumentId,
            String cause,
            String direction,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        if (amountPaise != null && amountPaise <= 0) {
            throw new IllegalArgumentException("A price must be greater than zero.");
        }
        String kind = actionKind(action);
        String method = paymentMethod == null || paymentMethod.isBlank() ? null : paymentMethod.trim();
        String why = cause == null || cause.isBlank() ? null : cause.trim();
        String way = direction == null || direction.isBlank() ? null : direction.trim();
        List<String> missing = moneyMissing(kind, partyId, amountPaise, method, why, occurredOn, occurredAt);
        long nextVersion = draft.getVersion() + 1;
        String extra = text(method) + "|" + text(allocatesDocumentId) + "|" + text(why) + "|" + text(way);
        draft.rememberMoney(method, allocatesDocumentId, why, way);
        draft.replace(
                kind,
                missing.isEmpty() ? DraftStatus.AWAITING_APPROVAL : DraftStatus.NEEDS_CLARIFICATION,
                hash(nextVersion, kind, partyId, null, null, null, null, amountPaise, occurredOn, occurredAt, extra),
                partyId,
                null,
                null,
                null,
                null,
                amountPaise,
                occurredOn,
                occurredAt,
                String.join(",", missing),
                Instant.now().plus(2, ChronoUnit.HOURS)
        );
        return view(draft);
    }

    private static List<String> moneyMissing(
            String kind,
            UUID partyId,
            Long amountPaise,
            String paymentMethod,
            String cause,
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
        if (partyId == null) {
            missing.add("party");
        }
        if (amountPaise == null) {
            missing.add("amount");
        }
        if ("PAYMENT".equals(kind) && paymentMethod == null) {
            missing.add("payment_method");
        }
        if ("RECEIVABLE".equals(kind) && cause == null) {
            missing.add("cause");
        }
        return missing;
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
        if (!"RECEIPT".equals(kind) && amountPaise == null) {
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
