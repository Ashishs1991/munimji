package in.munimsaathi.whatsapp;

import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyRepository;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.catalog.ProductRepository;
import in.munimsaathi.config.MunimProperties;
import in.munimsaathi.draft.Draft;
import in.munimsaathi.draft.DraftBook;
import in.munimsaathi.draft.DraftLine;
import in.munimsaathi.draft.DraftLineRepository;
import in.munimsaathi.draft.DraftProposal;
import in.munimsaathi.draft.DraftRepository;
import in.munimsaathi.draft.DraftStatus;
import in.munimsaathi.evidence.DocumentStore;
import in.munimsaathi.interpretation.CommandInterpreter;
import in.munimsaathi.interpretation.InterpretationOutcome;
import in.munimsaathi.reminder.ProviderReceipt;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.TenantContext;
import in.munimsaathi.voice.VoiceReceipts;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class WhatsAppTurn {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Set<String> APPROVAL = Set.of("yes", "y", "haan", "han", "ha", "haan ji", "हाँ", "हां");

    private final MunimProperties properties;
    private final InboundMessageRepository messages;
    private final CommandInterpreter interpreter;
    private final DraftBook drafts;
    private final DraftRepository draftRows;
    private final DraftLineRepository draftLines;
    private final PartyRepository parties;
    private final ProductRepository products;
    private final GodownRepository godowns;
    private final VoiceReceipts voice;
    private final DocumentStore documents;
    private final TenantContext tenants;
    private final SessionReply session;
    private final TransactionTemplate transactions;

    public WhatsAppTurn(
            MunimProperties properties,
            InboundMessageRepository messages,
            CommandInterpreter interpreter,
            DraftBook drafts,
            DraftRepository draftRows,
            DraftLineRepository draftLines,
            PartyRepository parties,
            ProductRepository products,
            GodownRepository godowns,
            VoiceReceipts voice,
            DocumentStore documents,
            TenantContext tenants,
            SessionReply session,
            TransactionTemplate transactions
    ) {
        this.properties = properties;
        this.messages = messages;
        this.interpreter = interpreter;
        this.drafts = drafts;
        this.draftRows = draftRows;
        this.draftLines = draftLines;
        this.parties = parties;
        this.products = products;
        this.godowns = godowns;
        this.voice = voice;
        this.documents = documents;
        this.tenants = tenants;
        this.session = session;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelay = 5_000)
    public void wake() {
        if (properties.whatsapp() == null || !properties.whatsapp().autoReply()) {
            return;
        }
        openStored();
    }

    public void openStored() {
        for (InboundMessage message : messages.findByStatus(InboundStatus.STORED)) {
            UUID id = message.getId();
            Prepared prepared;
            try {
                transactions.executeWithoutResult(status -> bindApproval(id));
                prepared = transactions.execute(status -> prepare(id));
            } catch (RuntimeException exception) {
                String phone = messages.findById(id).map(InboundMessage::getPhone).orElse(null);
                String text = exception.getMessage() == null ? "That message could not be read." : exception.getMessage();
                prepared = new Prepared(phone, text);
            }
            if (prepared == null || prepared.text() == null || prepared.phone() == null) {
                continue;
            }
            ProviderReceipt receipt;
            try {
                receipt = session.sendText(prepared.phone(), prepared.text());
            } catch (RuntimeException exception) {
                receipt = new ProviderReceipt("outcome_unknown", null);
            }
            if (receipt == null) {
                receipt = new ProviderReceipt("outcome_unknown", null);
            }
            String attempt = receipt.status();
            transactions.executeWithoutResult(status -> record(id, attempt));
        }
    }

    private void bindApproval(UUID id) {
        InboundMessage message = messages.findById(id).orElse(null);
        if (message == null || message.getStatus() != InboundStatus.STORED || message.getUserId() == null || message.isApprovalBound()) {
            return;
        }
        if (!approval(message.getBody())) {
            return;
        }
        List<Draft> pending = openDrafts(message.getUserId()).stream()
                .filter(draft -> draft.getStatus() == DraftStatus.AWAITING_APPROVAL)
                .toList();
        message.bindApproval(pending.size() == 1 ? pending.get(0).getId() : null);
    }

    private Prepared prepare(UUID id) {
        InboundMessage message = messages.findById(id).orElse(null);
        if (message == null || message.getStatus() != InboundStatus.STORED || message.getUserId() == null) {
            return null;
        }
        if (message.getPostedDocumentId() != null) {
            return new Prepared(message.getPhone(), "Posted. " + details(message.getUserId(), message.getDraftId()));
        }
        if (message.isApprovalBound()) {
            return approveBound(message);
        }
        if (message.getMediaId() != null) {
            return fromMedia(message);
        }
        if (message.getBody() == null || message.getBody().isBlank()) {
            return new Prepared(message.getPhone(), "Send the instruction as text.");
        }
        if (message.getDraftId() != null) {
            return new Prepared(message.getPhone(), details(message.getUserId(), message.getDraftId()));
        }
        LocalDate day = LocalDate.now(ZONE);
        Instant now = Instant.now();
        List<Draft> open = openDrafts(message.getUserId());
        UUID followUp = open.size() == 1 ? open.get(0).getId() : null;
        InterpretationOutcome outcome = interpreter.interpret(message.getUserId(), message.getBody(), followUp, day, now);
        if (outcome.draft() != null) {
            message.attachDraft(outcome.draft().id());
        }
        return new Prepared(message.getPhone(), reply(outcome));
    }

    private Prepared approveBound(InboundMessage message) {
        if (message.getDraftId() == null) {
            return new Prepared(message.getPhone(), "Yes approves only one current proposal.");
        }
        DraftProposal proposal = drafts.get(message.getUserId(), message.getDraftId());
        if (proposal.status() == DraftStatus.POSTED) {
            return new Prepared(message.getPhone(), "Posted. " + details(message.getUserId(), proposal.id()));
        }
        UUID documentId = drafts.approve(message.getUserId(), proposal.id(), proposal.version(), proposal.contentHash());
        message.recordPosting(documentId);
        return new Prepared(message.getPhone(), "Posted. " + details(message.getUserId(), proposal.id()));
    }

    private Prepared fromMedia(InboundMessage message) {
        String type = message.getMediaType() == null ? "" : message.getMediaType().toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        byte[] bytes = session.download(message.getMediaId());
        if (bytes == null || bytes.length == 0) {
            return new Prepared(message.getPhone(), "The file could not be downloaded.");
        }
        LocalDate day = LocalDate.now(ZONE);
        Instant now = Instant.now();
        if (type.startsWith("audio/")) {
            var capture = voice.capture(message.getUserId(), type, "whatsapp-voice", bytes, day, now);
            message.attachDraft(capture.draft().id());
            return new Prepared(message.getPhone(), details(message.getUserId(), capture.draft().id()));
        }
        if (type.startsWith("image/") || "application/pdf".equals(type)) {
            UUID draftId = message.getDraftId();
            if (draftId == null) {
                List<Draft> open = openDrafts(message.getUserId());
                if (open.size() == 1) {
                    draftId = open.get(0).getId();
                } else if (open.isEmpty()) {
                    draftId = drafts.propose(message.getUserId(), null, null, null, null, null, null, null, day, now).id();
                } else {
                    return new Prepared(message.getPhone(), "Say which draft this file belongs to.");
                }
                message.attachDraft(draftId);
            }
            var stored = documents.attach(message.getUserId(), draftId, type, "whatsapp", bytes);
            InterpretationOutcome outcome = documents.extract(message.getUserId(), stored.id(), day, now);
            if (outcome.draft() != null) {
                message.attachDraft(outcome.draft().id());
            }
            return new Prepared(message.getPhone(), reply(outcome));
        }
        return new Prepared(message.getPhone(), "Send a photo, PDF, or voice note.");
    }

    private void record(UUID id, String attempt) {
        messages.findById(id).ifPresent(message -> {
            if (message.getStatus() == InboundStatus.STORED) {
                message.recordAttempt(attempt);
            }
        });
    }

    private List<Draft> openDrafts(UUID userId) {
        UUID businessId = tenants.currentBusinessId(userId);
        return draftRows.findByBusinessIdAndStatusIn(
                businessId,
                List.of(DraftStatus.NEEDS_CLARIFICATION, DraftStatus.AWAITING_APPROVAL)
        ).stream().filter(draft -> !draft.getExpiresAt().isBefore(Instant.now())).toList();
    }

    private String reply(InterpretationOutcome outcome) {
        String text = outcome.answer() != null ? outcome.answer() : details(outcome.draft());
        if (outcome.ewayDraftId() != null) {
            text = text + " E-way draft " + outcome.ewayDraftId() + " is saved and not submitted.";
        }
        return text;
    }

    private String details(DraftProposal draft) {
        if (draft == null) {
            return "I could not make a draft from that. Say the party, product, quantity, unit, and price.";
        }
        return details(null, draft.id());
    }

    private String details(UUID userId, UUID draftId) {
        if (draftId == null) {
            return "I could not make a draft from that. Say the party, product, quantity, unit, and price.";
        }
        Draft draft = draftRows.findById(draftId).orElse(null);
        if (draft == null) {
            return "I could not make a draft from that. Say the party, product, quantity, unit, and price.";
        }
        String facts = facts(draft);
        if (draft.getStatus() == DraftStatus.AWAITING_APPROVAL) {
            return "Reply Yes to post this draft only: " + facts + ".";
        }
        if (draft.getStatus() == DraftStatus.POSTED) {
            return facts + ".";
        }
        String missing = draft.getMissingFields() == null || draft.getMissingFields().isBlank()
                ? "a correction"
                : draft.getMissingFields();
        return "Still need " + missing + ". So far: " + facts + ".";
    }

    private String facts(Draft draft) {
        List<DraftLine> lines = draftLines.findByDraftIdOrderByLineNumberAsc(draft.getId());
        StringBuilder text = new StringBuilder();
        Party party = draft.getCustomerId() == null ? null : parties.findById(draft.getCustomerId()).orElse(null);
        text.append(name(party));
        text.append(", ");
        if (lines.isEmpty()) {
            text.append(quantity(draft.getDisplayQuantity(), draft.getUnitLabel()));
            text.append(" ");
            text.append(productName(draft.getProductId()));
        } else {
            for (int index = 0; index < lines.size(); index++) {
                DraftLine line = lines.get(index);
                if (index > 0) {
                    text.append("; ");
                }
                text.append(quantity(line.getDisplayQuantity(), line.getUnitLabel()));
                text.append(" ");
                text.append(productName(line.getProductId()));
            }
        }
        text.append(" at ");
        text.append(godownName(draft.getLocationId()));
        if (draft.getAmountPaise() != null) {
            text.append(", ₹").append(BigDecimal.valueOf(draft.getAmountPaise(), 2).toPlainString());
        }
        return text.toString();
    }

    private static String name(Party party) {
        return party == null ? "party missing" : party.getDisplayName();
    }

    private String productName(UUID productId) {
        if (productId == null) {
            return "product missing";
        }
        return products.findById(productId).map(Product::getName).orElse("product missing");
    }

    private String godownName(UUID locationId) {
        if (locationId == null) {
            return "godown missing";
        }
        return godowns.findById(locationId).map(Godown::getName).orElse("godown missing");
    }

    private static String quantity(BigDecimal quantity, String unit) {
        String amount = quantity == null ? "quantity missing" : quantity.stripTrailingZeros().toPlainString();
        return unit == null || unit.isBlank() ? amount : amount + " " + unit;
    }

    private static boolean approval(String body) {
        return body != null && APPROVAL.contains(body.trim().toLowerCase(Locale.ROOT));
    }

    private record Prepared(String phone, String text) {
    }
}
