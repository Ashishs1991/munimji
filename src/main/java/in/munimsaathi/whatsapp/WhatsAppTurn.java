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
import in.munimsaathi.interpretation.ConversationMemory;
import in.munimsaathi.interpretation.InterpretationOutcome;
import in.munimsaathi.interpretation.PendingBalanceQuestions;
import in.munimsaathi.reminder.ProviderReceipt;
import in.munimsaathi.reminder.ReminderBook;
import in.munimsaathi.reminder.ReminderStatus;
import in.munimsaathi.reminder.ReminderTask;
import in.munimsaathi.reminder.ReminderTaskRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class WhatsAppTurn {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Set<String> APPROVAL = Set.of("yes", "y", "haan", "han", "ha", "haan ji", "हाँ", "हां");
    private static final Set<String> CANCEL = Set.of("cancel", "ruko", "chhodo");
    private static final Set<String> RESUME = Set.of("resume", "wapas");

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
    private final ReminderBook reminders;
    private final ReminderTaskRepository reminderTasks;
    private final SessionReply session;
    private final TransactionTemplate transactions;
    private final ConversationMemory memory;
    private final PendingBalanceQuestions questions;

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
            ReminderBook reminders,
            ReminderTaskRepository reminderTasks,
            SessionReply session,
            TransactionTemplate transactions,
            ConversationMemory memory,
            PendingBalanceQuestions questions
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
        this.reminders = reminders;
        this.reminderTasks = reminderTasks;
        this.session = session;
        this.transactions = transactions;
        this.memory = memory;
        this.questions = questions;
    }

    @Scheduled(fixedDelay = 5_000)
    public void wake() {
        if (properties.whatsapp() == null || !properties.whatsapp().autoReply()) {
            return;
        }
        openStored();
    }

    public synchronized void openStored() {
        for (InboundMessage message : messages.findByStatus(InboundStatus.STORED)) {
            UUID id = message.getId();
            Prepared prepared;
            try {
                transactions.executeWithoutResult(status -> bindApproval(id));
                prepared = transactions.execute(status -> {
                    Prepared reply = prepare(id);
                    rememberShownProposal(id, reply);
                    return reply;
                });
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

    private void rememberShownProposal(UUID id, Prepared reply) {
        if (reply == null || reply.text() == null || !reply.text().contains("Is entry ko confirm karne ke liye")) {
            return;
        }
        InboundMessage message = messages.findById(id).orElseThrow();
        if (message.isApprovalBound() || message.getDraftId() == null) {
            return;
        }
        Draft draft = draftRows.findById(message.getDraftId()).orElseThrow();
        if (draft.getStatus() == DraftStatus.AWAITING_APPROVAL) {
            message.rememberShownProposal(draft.getPresentedVersion(), draft.getPresentedHash());
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
        tenants.inBusiness(message.getUserId(), message.getBusinessId(), () -> {
            var shown = messages.latestShownProposal(message.getBusinessId(), message.getUserId(), message.getPhone());
            if (shown.isPresent()) {
                InboundMessage proposal = shown.get();
                message.bindApproval(proposal.getDraftId(), proposal.getDraftVersion(), proposal.getContentHash());
                return null;
            }
            List<Draft> pending = draftRows.findByBusinessIdAndStatus(
                    message.getBusinessId(),
                    DraftStatus.AWAITING_APPROVAL
            ).stream().filter(draft -> !draft.getExpiresAt().isBefore(Instant.now())).toList();
            List<ReminderTask> waiting = reminderTasks.findByBusinessIdAndStatusIn(
                    message.getBusinessId(),
                    List.of(ReminderStatus.AWAITING_APPROVAL, ReminderStatus.NEEDS_REAPPROVAL)
            );
            if (pending.size() + waiting.size() != 1) {
                message.bindApproval(null);
                return null;
            }
            if (pending.size() == 1) {
                Draft draft = pending.get(0);
                long version = draft.getPresentedVersion() == null ? draft.getVersion() : draft.getPresentedVersion();
                String hash = draft.getPresentedHash() == null ? draft.getContentHash() : draft.getPresentedHash();
                message.bindApproval(draft.getId(), version, hash);
            } else {
                message.bindReminder(waiting.get(0).getId());
            }
            return null;
        });
    }

    private Prepared prepare(UUID id) {
        InboundMessage message = messages.findById(id).orElse(null);
        if (message == null || message.getStatus() != InboundStatus.STORED || message.getUserId() == null) {
            return null;
        }
        return tenants.inBusiness(message.getUserId(), message.getBusinessId(), () -> prepareInBusiness(message));
    }

    private Prepared prepareInBusiness(InboundMessage message) {
        if (message.getPostedDocumentId() != null) {
            return new Prepared(message.getPhone(), "Posted. " + details(message.getUserId(), message.getDraftId()));
        }
        if (message.isApprovalBound()) {
            return approveBound(message);
        }
        if (message.getDraftId() != null) {
            return new Prepared(message.getPhone(), details(message.getUserId(), message.getDraftId()));
        }
        if (message.getMediaId() != null) {
            return fromMedia(message);
        }
        if (message.getBody() == null || message.getBody().isBlank()) {
            return new Prepared(message.getPhone(), "Send the instruction as text.");
        }
        String word = message.getBody().trim().toLowerCase(Locale.ROOT);
        if (word.startsWith("entry ")) {
            String selected = word.substring(6).trim();
            Draft chosen = openDrafts(message.getUserId()).stream()
                    .filter(draft -> draft.getId().toString().equals(selected)).findFirst().orElse(null);
            if (chosen == null) {
                return new Prepared(message.getPhone(), "Yeh entry abhi pending nahi hai. Sahi entry ID bhejiye.");
            }
            message.attachDraft(chosen.getId());
            return new Prepared(message.getPhone(), details(message.getUserId(), chosen.getId()));
        }
        if (CANCEL.contains(word)) {
            return remembered(message, cancelOpen(message));
        }
        if (RESUME.contains(word)) {
            return remembered(message, resumeOpen(message));
        }
        LocalDate day = LocalDate.now(ZONE);
        Instant now = Instant.now();
        InterpretationOutcome outcome = interpreter.interpret(message.getUserId(), message.getBody(), null, day, now);
        if (outcome.draft() != null) {
            message.attachDraft(outcome.draft().id());
        }
        return remembered(message, reply(outcome));
    }

    private Prepared approveBound(InboundMessage message) {
        if (message.getReminderTaskId() != null) {
            reminders.approve(message.getUserId(), message.getReminderTaskId());
            return new Prepared(message.getPhone(), "Reminder approved. It stays on the dashboard and is not sent to the customer.");
        }
        if (message.getDraftId() == null || message.getDraftVersion() == null || message.getContentHash() == null) {
            List<Draft> pending = openDrafts(message.getUserId()).stream()
                    .filter(draft -> draft.getStatus() == DraftStatus.AWAITING_APPROVAL).toList();
            if (!pending.isEmpty()) {
                StringBuilder choices = new StringBuilder("Kaunsi entry confirm karni hai? Neeche se entry ID chuniye; pehle uska poora draft dikhaunga.\n");
                for (Draft draft : pending) {
                    choices.append("entry ").append(draft.getId()).append(" — ").append(facts(draft)).append("\n");
                }
                return new Prepared(message.getPhone(), choices.toString().trim());
            }
            return new Prepared(message.getPhone(), "Yes approves only one current proposal.");
        }
        Draft stored = draftRows.findById(message.getDraftId()).orElse(null);
        if (stored != null && stored.getStatus() == DraftStatus.POSTED) {
            return new Prepared(message.getPhone(), "Posted. " + details(message.getUserId(), stored.getId()));
        }
        UUID documentId = drafts.approve(
                message.getUserId(),
                message.getDraftId(),
                message.getDraftVersion(),
                message.getContentHash()
        );
        message.recordPosting(documentId);
        return new Prepared(message.getPhone(), "Posted. " + details(message.getUserId(), message.getDraftId()));
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
            if (capture.draft() != null) {
                message.attachDraft(capture.draft().id());
            }
            String text = capture.draft() == null
                    ? (capture.answer() == null ? "Namaste. Tell me what was received, sold, or paid." : capture.answer())
                    : reply(new InterpretationOutcome(capture.draft(), List.of(), null, capture.answer()));
            return remembered(message, capture.transcript(), text);
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

    private Prepared remembered(InboundMessage message, String text) {
        return remembered(message, message.getBody(), text);
    }

    private Prepared remembered(InboundMessage message, String ownerText, String text) {
        text = HinglishReplies.render(text);
        memory.remember(message.getUserId(), "owner", ownerText);
        memory.remember(message.getUserId(), "assistant", text);
        return new Prepared(message.getPhone(), text);
    }

    private String cancelOpen(InboundMessage message) {
        if (questions.get(message.getUserId()).isPresent()) {
            questions.clear(message.getUserId());
            return "Cancelled the balance question. Nothing was posted.";
        }
        List<Draft> open = openDrafts(message.getUserId());
        if (open.size() == 1) {
            drafts.cancel(message.getUserId(), open.get(0).getId());
            return "Cancelled. Nothing was posted.";
        }
        if (open.isEmpty()) {
            return "There is no open draft to cancel.";
        }
        return "Which draft should I cancel?";
    }

    private String resumeOpen(InboundMessage message) {
        List<Draft> suspended = draftRows.findByBusinessIdAndStatus(message.getBusinessId(), DraftStatus.SUSPENDED).stream()
                .filter(draft -> !draft.getExpiresAt().isBefore(Instant.now()))
                .toList();
        if (suspended.size() == 1) {
            drafts.resume(message.getUserId(), suspended.get(0).getId());
            return "Resumed. " + details(message.getUserId(), suspended.get(0).getId());
        }
        if (suspended.isEmpty()) {
            return "There is no suspended draft to resume.";
        }
        return "Which draft should I resume?";
    }

    private List<Draft> openDrafts(UUID userId) {
        UUID businessId = tenants.currentBusinessId(userId);
        return draftRows.findByBusinessIdAndStatusIn(
                businessId,
                List.of(DraftStatus.NEEDS_CLARIFICATION, DraftStatus.AWAITING_APPROVAL)
        ).stream().filter(draft -> !draft.getExpiresAt().isBefore(Instant.now())).toList();
    }

    private String reply(InterpretationOutcome outcome) {
        if (outcome.draft() != null) {
            Draft pending = draftRows.findById(outcome.draft().id()).orElse(null);
            if (pending != null && "OUTGOING_PAYMENT".equals(pending.getKind()) && pending.getStatus() == DraftStatus.NEEDS_CLARIFICATION) {
                return outgoingQuestion(pending, outcome.answer());
            }
        }
        String facts = outcome.draft() == null ? null : details(outcome.draft());
        String text;
        if (outcome.answer() != null && facts != null) {
            text = outcome.answer() + " " + facts;
        } else if (outcome.answer() != null) {
            text = outcome.answer();
        } else if (facts != null) {
            text = facts;
        } else {
            text = "Namaste. Tell me what was received, sold, or paid.";
        }
        if (outcome.ewayDraftId() != null) {
            text = text + " E-way draft " + outcome.ewayDraftId() + " is saved and not submitted.";
        }
        return text;
    }

    private String outgoingQuestion(Draft draft, String note) {
        Party party = draft.getCustomerId() == null ? null : parties.findById(draft.getCustomerId()).orElse(null);
        if (party == null) {
            return note == null ? "Kisko paise diye? Party ka poora naam bataiye." : note;
        }
        if (draft.getAmountPaise() == null) {
            return party.getDisplayName() + " ko kitne rupaye diye?";
        }
        String facts = "Samjha: aapne " + party.getDisplayName() + " ko ₹" + BigDecimal.valueOf(draft.getAmountPaise(), 2).toPlainString() + " diye. ";
        if (draft.getMissingFields().contains("payment_purpose")) {
            return facts + "Purane bill ka bakaya chukaya, advance diya, ya udhaar diya?";
        }
        return facts + "Cash se ya UPI se diye?";
    }

    private String details(DraftProposal draft) {
        if (draft == null) {
            return "Namaste. Tell me what was received, sold, or paid.";
        }
        return details(null, draft.id());
    }

    private String details(UUID userId, UUID draftId) {
        if (draftId == null) {
            return "Namaste. Tell me what was received, sold, or paid.";
        }
        Draft draft = draftRows.findById(draftId).orElse(null);
        if (draft == null) {
            return "Namaste. Tell me what was received, sold, or paid.";
        }
        String facts = facts(draft);
        if (draft.getStatus() == DraftStatus.AWAITING_APPROVAL) {
            draft.present();
            return "Reply Yes to post this draft only: " + facts + ".";
        }
        if (draft.getStatus() == DraftStatus.POSTED) {
            return facts + ".";
        }
        String missing = humanMissing(draft.getMissingFields());
        if (facts.isBlank()) {
            return "Still need " + missing + ".";
        }
        return "Still need " + missing + ". So far: " + facts + ".";
    }

    private static String humanMissing(String raw) {
        if (raw == null || raw.isBlank()) {
            return "a correction";
        }
        String[] fields = raw.split(",");
        List<String> words = new ArrayList<>();
        for (String field : fields) {
            String word = switch (field.trim()) {
                case "location" -> "the godown";
                case "party" -> "the party";
                case "product" -> "the product";
                case "quantity" -> "the quantity";
                case "unit" -> "the unit";
                case "price" -> "the price";
                case "action" -> "whether this is a receipt, a sale, or a payment";
                case "date" -> "the date";
                case "amount" -> "the amount";
                case "payment_method" -> "cash or UPI";
                case "payment_purpose" -> "payment ka maksad (purana bill, advance, ya udhaar)";
                case "cause" -> "whether this amount is new";
                default -> field.startsWith("line-") ? "line " + field.substring("line-".length()) : field;
            };
            if (!word.isBlank()) {
                words.add(word);
            }
        }
        if (words.isEmpty()) {
            return "a correction";
        }
        if (words.size() == 1) {
            return words.get(0);
        }
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    private String facts(Draft draft) {
        List<DraftLine> lines = draftLines.findByDraftIdOrderByLineNumberAsc(draft.getId());
        StringBuilder text = new StringBuilder();
        Party party = draft.getCustomerId() == null ? null : parties.findById(draft.getCustomerId()).orElse(null);
        if ("PAYMENT".equals(draft.getKind()) || "RECEIVABLE".equals(draft.getKind()) || "PAYABLE".equals(draft.getKind()) || "OUTGOING_PAYMENT".equals(draft.getKind())) {
            return moneyFacts(draft, party);
        }
        if (party != null) {
            text.append(party.getDisplayName());
        }
        if (lines.isEmpty()) {
            appendPiece(text, knownQuantity(draft.getDisplayQuantity(), draft.getUnitLabel()));
            appendPiece(text, knownProduct(draft.getProductId()));
        } else {
            for (DraftLine line : lines) {
                StringBuilder piece = new StringBuilder();
                String quantity = knownQuantity(line.getDisplayQuantity(), line.getUnitLabel());
                String product = knownProduct(line.getProductId());
                if (quantity != null) {
                    piece.append(quantity);
                }
                if (product != null) {
                    if (!piece.isEmpty()) {
                        piece.append(' ');
                    }
                    piece.append(product);
                }
                if (!piece.isEmpty()) {
                    if (!text.isEmpty()) {
                        text.append("; ");
                    }
                    text.append(piece);
                }
            }
        }
        String godown = draft.getLocationId() == null ? null : godowns.findById(draft.getLocationId()).map(Godown::getName).orElse(null);
        if (godown != null) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append("at ").append(godown);
        }
        if (draft.getPaymentMethod() != null) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(draft.getPaymentMethod());
        }
        if (draft.getAmountPaise() != null) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append("₹").append(BigDecimal.valueOf(draft.getAmountPaise(), 2).toPlainString());
        }
        return text.toString();
    }

    private static String moneyFacts(Draft draft, Party party) {
        String name = party == null ? "party not selected" : party.getDisplayName();
        String amount = draft.getAmountPaise() == null ? "amount not stated" : "₹" + BigDecimal.valueOf(draft.getAmountPaise(), 2).toPlainString();
        boolean posted = draft.getStatus() == DraftStatus.POSTED;
        if ("OUTGOING_PAYMENT".equals(draft.getKind())) {
            String purpose = switch (draft.getCause() == null ? "" : draft.getCause()) {
                case "bill_payment" -> "purane bakaye ki payment; bakaya ghatega";
                case "advance" -> "supplier ko advance; supplier ke paas aapka credit record hoga";
                case "loan" -> "udhaar diya; aapko unse paise lene hain";
                default -> "payment ka maksad abhi confirm karna hai";
            };
            String method = draft.getPaymentMethod() == null ? "Cash ya UPI abhi batana hai" : draft.getPaymentMethod();
            return (posted ? "Record ho gaya: " : "Record karna hai: ") + "aapne " + name + " ko " + amount
                    + " diye. " + purpose + ". " + method + ". Stock mein koi badlav nahi";
        }
        if ("PAYMENT".equals(draft.getKind())) {
            String method = draft.getPaymentMethod() == null ? "payment method not stated" : draft.getPaymentMethod();
            String allocation = draft.getAllocatesDocumentId() == null
                    ? "Not allocated to an invoice"
                    : "Allocated to document " + draft.getAllocatesDocumentId();
            return (posted ? "Money received from " : "Record money received from ") + name + ": " + amount + " via " + method
                    + ". " + allocation + ". " + (posted ? "Reduced" : "Will reduce") + " their receivable by " + amount
                    + "; an excess stays as customer credit. Stock " + (posted ? "was" : "will be") + " unchanged";
        }
        if ("RECEIVABLE".equals(draft.getKind())) {
            return (posted ? "New receivable recorded: " : "Record a new receivable: ") + name + " owes you " + amount
                    + ". This " + (posted ? "increased" : "will increase") + " what they owe by " + amount
                    + ". Stock " + (posted ? "was" : "will be") + " unchanged";
        }
        return (posted ? "Supplier obligation recorded: " : "Record a supplier obligation: ") + "you owe " + name + " " + amount
                + ". This " + (posted ? "increased" : "will increase") + " your payable by " + amount
                + ". Stock " + (posted ? "was" : "will be") + " unchanged";
    }

    private static void appendPiece(StringBuilder text, String piece) {
        if (piece == null) {
            return;
        }
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(piece);
    }

    private String knownProduct(UUID productId) {
        if (productId == null) {
            return null;
        }
        return products.findById(productId).map(Product::getName).orElse(null);
    }

    private static String knownQuantity(BigDecimal quantity, String unit) {
        if (quantity == null && (unit == null || unit.isBlank())) {
            return null;
        }
        if (quantity == null) {
            return unit;
        }
        return unit == null || unit.isBlank() ? quantity.stripTrailingZeros().toPlainString() : quantity.stripTrailingZeros().toPlainString() + " " + unit;
    }

    private static boolean approval(String body) {
        return body != null && APPROVAL.contains(body.trim().toLowerCase(Locale.ROOT));
    }

    private record Prepared(String phone, String text) {
        private Prepared {
            text = HinglishReplies.render(text);
        }
    }
}
