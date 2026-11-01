package in.munimsaathi.whatsapp;

import in.munimsaathi.config.MunimProperties;
import in.munimsaathi.draft.DraftBook;
import in.munimsaathi.draft.DraftProposal;
import in.munimsaathi.draft.DraftStatus;
import in.munimsaathi.interpretation.CommandInterpreter;
import in.munimsaathi.interpretation.InterpretationOutcome;
import in.munimsaathi.reminder.ProviderReceipt;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final SessionReply session;
    private final TransactionTemplate transactions;

    public WhatsAppTurn(
            MunimProperties properties,
            InboundMessageRepository messages,
            CommandInterpreter interpreter,
            DraftBook drafts,
            SessionReply session,
            TransactionTemplate transactions
    ) {
        this.properties = properties;
        this.messages = messages;
        this.interpreter = interpreter;
        this.drafts = drafts;
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
                prepared = transactions.execute(status -> prepare(id));
            } catch (RuntimeException exception) {
                String phone = messages.findById(id).map(InboundMessage::getPhone).orElse(null);
                String text = exception.getMessage() == null ? "That message could not be read." : exception.getMessage();
                prepared = new Prepared(phone, text, true);
            }
            if (prepared == null || prepared.text() == null || prepared.phone() == null) {
                continue;
            }
            ProviderReceipt receipt = session.sendText(prepared.phone(), prepared.text());
            if (prepared.finish() && receipt != null) {
                transactions.executeWithoutResult(status -> finish(id));
            }
        }
    }

    private Prepared prepare(UUID id) {
        InboundMessage message = messages.findById(id).orElse(null);
        if (message == null || message.getStatus() != InboundStatus.STORED || message.getUserId() == null) {
            return null;
        }
        if (message.getBody() == null || message.getBody().isBlank()) {
            return new Prepared(message.getPhone(), "Send the instruction as text.", true);
        }
        if (approval(message.getBody())) {
            drafts.approveCurrent(message.getUserId());
            return new Prepared(message.getPhone(), "Posted.", true);
        }
        if (message.getDraftId() != null) {
            return new Prepared(message.getPhone(), summary(drafts.get(message.getUserId(), message.getDraftId())), true);
        }
        LocalDate day = LocalDate.now(ZONE);
        InterpretationOutcome outcome = interpreter.interpret(message.getUserId(), message.getBody(), day, Instant.now());
        if (outcome.draft() != null) {
            message.attachDraft(outcome.draft().id());
        }
        return new Prepared(message.getPhone(), summary(outcome.draft()), true);
    }

    private void finish(UUID id) {
        messages.findById(id).ifPresent(message -> {
            if (message.getStatus() == InboundStatus.STORED) {
                message.markReplied();
            }
        });
    }

    private static String summary(DraftProposal draft) {
        if (draft == null) {
            return "I could not make a draft from that. Say the party, product, quantity, unit, and price.";
        }
        if (draft.status() == DraftStatus.AWAITING_APPROVAL) {
            if (draft.missingFields().size() == 1 && "price".equals(draft.missingFields().get(0))) {
                return "Sale draft is ready without a price. Reply Yes to record it. The money stays pending.";
            }
            String amount = draft.amountPaise() == null
                    ? "the stated quantity"
                    : "₹" + BigDecimal.valueOf(draft.amountPaise(), 2).toPlainString();
            return "Sale draft is ready for " + amount + ". Reply Yes to post it.";
        }
        if (draft.missingFields().isEmpty()) {
            return "The draft needs a correction before it can be posted.";
        }
        return "Missing: " + String.join(", ", draft.missingFields()) + ". I will not guess those.";
    }

    private static boolean approval(String body) {
        return APPROVAL.contains(body.trim().toLowerCase(Locale.ROOT));
    }

    private record Prepared(String phone, String text, boolean finish) {
    }
}
