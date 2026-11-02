package in.munimsaathi.interpretation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Names;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.draft.DraftBook;
import in.munimsaathi.draft.DraftLineSpec;
import in.munimsaathi.draft.DraftProposal;
import in.munimsaathi.eway.EwayBook;
import in.munimsaathi.eway.EwayDetails;
import in.munimsaathi.eway.EwayParty;
import in.munimsaathi.eway.EwayTax;
import in.munimsaathi.ledger.PostingService;
import in.munimsaathi.location.LocationBook;
import in.munimsaathi.location.Place;
import in.munimsaathi.reminder.ReminderBook;
import in.munimsaathi.reminder.ReminderKind;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class CommandInterpreter {

    private static final Set<String> READ_TOOLS = Set.of(
            "search_products",
            "get_party_candidates",
            "get_stock",
            "locate_goods",
            "get_forecast",
            "get_import_status",
            "get_party_balance",
            "get_sales_summary"
    );

    private final LanguageModel model;
    private final ObjectMapper mapper;
    private final TenantContext tenants;
    private final PartyBook parties;
    private final CatalogService catalog;
    private final GodownRepository godowns;
    private final DraftBook drafts;
    private final EwayBook eway;
    private final PostingService posting;
    private final LocationBook locations;
    private final ReminderBook reminders;
    private final ConversationMemory memory;
    private final PendingBalanceQuestions questions;
    private final DialogueState dialogue;

    public CommandInterpreter(
            LanguageModel model,
            ObjectMapper mapper,
            TenantContext tenants,
            PartyBook parties,
            CatalogService catalog,
            GodownRepository godowns,
            DraftBook drafts,
            EwayBook eway,
            PostingService posting,
            LocationBook locations,
            ReminderBook reminders,
            ConversationMemory memory,
            PendingBalanceQuestions questions,
            DialogueState dialogue
    ) {
        this.model = model;
        this.mapper = mapper;
        this.tenants = tenants;
        this.parties = parties;
        this.catalog = catalog;
        this.godowns = godowns;
        this.drafts = drafts;
        this.eway = eway;
        this.posting = posting;
        this.locations = locations;
        this.reminders = reminders;
        this.memory = memory;
        this.questions = questions;
        this.dialogue = dialogue;
    }

    @Transactional
    public InterpretationOutcome interpret(UUID userId, String text, LocalDate occurredOn, Instant occurredAt) {
        return interpret(userId, text, null, occurredOn, occurredAt);
    }

    @Transactional
    public InterpretationOutcome interpret(UUID userId, String text, UUID draftId, LocalDate occurredOn, Instant occurredAt) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A command is required.");
        }
        InterpretationOutcome direct = answerQuestion(userId, text, occurredOn, occurredAt);
        if (direct != null) return traced(userId, text, null, direct);
        TurnContext.set(context(userId));
        String modelText;
        try {
            modelText = model.complete(text);
        } finally {
            TurnContext.clear();
        }
        JsonNode reply = commandJson(modelText);
        if (reply == null) {
            return traced(userId, text, modelText, new InterpretationOutcome(null, List.of(), null, modelText.trim()));
        }
        reply = prepareTurn(userId, text, singleLine(reply));
        return traced(userId, text, modelText, finish(userId, rememberHeard(userId, reply, apply(userId, draftId, reply, occurredOn, occurredAt))));
    }

    @Transactional
    public InterpretationOutcome applyExtraction(
            UUID userId,
            UUID draftId,
            String modelJson,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        JsonNode reply = commandJson(modelJson);
        if (reply == null) {
            return new InterpretationOutcome(null, List.of(), null, modelJson == null ? "" : modelJson.trim());
        }
        var extracted = (com.fasterxml.jackson.databind.node.ObjectNode) singleLine(reply).deepCopy();
        extracted.remove(List.of("create_party", "create_product"));
        return finish(userId, rememberHeard(userId, extracted, apply(userId, draftId, extracted, occurredOn, occurredAt)));
    }

    private JsonNode singleLine(JsonNode reply) {
        JsonNode lines = reply.get("lines");
        if (lines == null || !lines.isArray() || lines.size() != 1) return reply;
        var fields = (com.fasterxml.jackson.databind.node.ObjectNode) reply.deepCopy();
        for (String key : List.of("product_name", "quantity", "unit", "amount_paise")) {
            if ((fields.get(key) == null || fields.get(key).isNull()) && lines.get(0).hasNonNull(key)) fields.set(key, lines.get(0).get(key));
        }
        fields.remove("lines");
        return fields;
    }

    private InterpretationOutcome traced(UUID userId, String source, String modelReply, InterpretationOutcome outcome) {
        UUID id = outcome.draft() == null ? null : outcome.draft().id();
        dialogue.recordTurn(userId, source, modelReply, id, id == null ? null : drafts.kindOf(userId, id));
        return outcome;
    }

    @Transactional(readOnly = true)
    public boolean clarificationPending(UUID userId) {
        var state = dialogue.get(userId).orElse(null);
        UUID focused = focusedDraft(userId);
        return state != null && focused != null && (state.question() != null
                || drafts.inspect(userId, focused).getStatus() == in.munimsaathi.draft.DraftStatus.NEEDS_CLARIFICATION);
    }

    @Transactional(readOnly = true)
    public boolean offeredQuestionPending(UUID userId) {
        return dialogue.get(userId).map(state -> state.question() != null && focusedDraft(userId) != null).orElse(false);
    }

    @Transactional
    public String focus(UUID userId, UUID draftId) {
        if (drafts.open(userId).stream().noneMatch(row -> row.id().equals(draftId))) throw new IllegalArgumentException("Entry pending nahi hai.");
        var draft = drafts.inspect(userId, draftId);
        var proposal = new DraftProposal(draft.getId(), draft.getVersion(), draft.getContentHash(), draft.getStatus(),
                draft.getMissingFields().isBlank() ? List.of() : List.of(draft.getMissingFields().split(",")), draft.getAmountPaise());
        return finish(userId, new InterpretationOutcome(proposal, List.of(), null, null)).answer();
    }

    @Transactional
    public void clearDialogue(UUID userId) { dialogue.clear(userId); }

    private UUID focusedDraft(UUID userId) {
        var state = dialogue.get(userId).orElse(null);
        if (state == null) return null;
        return drafts.open(userId).stream().filter(row -> row.id().equals(state.draftId())).map(DraftBook.OpenDraft::id).findFirst().orElse(null);
    }

    private InterpretationOutcome finish(UUID userId, InterpretationOutcome outcome) {
        if (outcome.draft() == null) return outcome;
        var draft = drafts.inspect(userId, outcome.draft().id());
        dialogue.save(userId, draft.getId(), null, null, null);
        if (draft.getStatus() != in.munimsaathi.draft.DraftStatus.NEEDS_CLARIFICATION || "LOCATION".equals(draft.getKind())) return outcome;
        if (draft.getKind() == null && (draft.getDisplayQuantity() != null || draft.getHeardProduct() != null)) {
            dialogue.save(userId, draft.getId(), "STOCK_ACTION", null, null);
            return withAnswer(outcome, "Quantity aur maal ki baat samajh gaya. Kya naya maal aaya hai, bika hai, ya sirf uski jagah note karni hai? Stock abhi nahi badla.");
        }
        if (draft.getCustomerId() == null && draft.getHeardParty() != null) {
            var named = parties.findByName(userId, draft.getHeardParty());
            if (named.size() == 1 && ("RECEIPT".equals(draft.getKind()) || "SALE".equals(draft.getKind()))) {
                String role = "RECEIPT".equals(draft.getKind()) ? "supplier" : "customer";
                dialogue.save(userId, draft.getId(), "USE_PARTY", named.get(0).getDisplayName(), role);
                return withAnswer(outcome, questionForDraft(userId, draft.getId(), "USE_PARTY", named.get(0).getDisplayName(), role));
            }
        }
        if (draft.getCustomerId() == null && draft.getHeardParty() != null && parties.findByName(userId, draft.getHeardParty()).isEmpty()) {
            Party close = closeParty(userId, draft.getHeardParty());
            if (close != null) {
                dialogue.save(userId, draft.getId(), "MATCH_PARTY", close.getDisplayName(), null);
                return withAnswer(outcome, partyNote(userId, draft.getHeardParty(), "RECEIPT".equals(draft.getKind()))
                        + " Kya wahi party hai? Haan ya sahi naam bataiye.");
            }
            dialogue.save(userId, draft.getId(), "CREATE_PARTY", draft.getHeardParty(), null);
            return withAnswer(outcome, questionForDraft(userId, draft.getId(), "CREATE_PARTY", draft.getHeardParty(), null));
        }
        if (("RECEIPT".equals(draft.getKind()) || "SALE".equals(draft.getKind())) && draft.getProductId() == null
                && draft.getHeardProduct() != null && catalog.candidates(userId, draft.getHeardProduct()).isEmpty()) {
            String kind = draft.getUnitLabel() == null ? "PRODUCT_UNIT" : "CREATE_PRODUCT";
            dialogue.save(userId, draft.getId(), kind, draft.getHeardProduct(), draft.getUnitLabel());
            return withAnswer(outcome, questionForDraft(userId, draft.getId(), kind, draft.getHeardProduct(), draft.getUnitLabel()));
        }
        return outcome;
    }

    private static InterpretationOutcome withAnswer(InterpretationOutcome outcome, String answer) {
        return new InterpretationOutcome(outcome.draft(), outcome.refusedTools(), outcome.ewayDraftId(), answer);
    }

    private String questionForDraft(UUID userId, UUID draftId, String question, String name, String unit) {
        var draft = drafts.inspect(userId, draftId);
        StringBuilder facts = new StringBuilder();
        if (draft.getDisplayQuantity() != null) facts.append(draft.getDisplayQuantity().stripTrailingZeros().toPlainString()).append(" ").append(draft.getUnitLabel()).append(" ");
        if (draft.getHeardProduct() != null) facts.append(draft.getHeardProduct()).append(" ");
        if (draft.getLocationId() != null) godowns.findById(draft.getLocationId()).ifPresent(place -> facts.append("— ").append(place.getName()).append(" "));
        if (draft.getAmountPaise() != null) facts.append("₹").append(BigDecimal.valueOf(draft.getAmountPaise(), 2).toPlainString()).append(" ");
        String text = creationQuestion(question, name, unit);
        if ("CREATE_PARTY".equals(question) && "RECEIPT".equals(draft.getKind())) text = text.replace("nayi party", "nayi supplier party");
        return (facts.isEmpty() ? "" : "Samjha: " + facts.toString().trim() + ". ") + text;
    }

    private String creationQuestion(String question, String name, String unit) {
        if ("USE_PARTY".equals(question)) return "“" + name + "” saved hai, lekin is entry ke liye " + unit + " role chahiye. Kya inhe " + unit + " bhi maan kar wahi party use karun? Haan ya doosri party ka naam batayein. Paise abhi nahi badlenge.";
        if ("CREATE_PARTY".equals(question)) return "“" + name + "” saved parties mein nahi hai. Kya is naam ki nayi party banaun? Haan bolein, ya purani party ka saved naam batayein. Baaki entry yaad hai.";
        if ("PRODUCT_UNIT".equals(question)) return "“" + name + "” naya product banana ho to stock kis base unit mein ginein—bori, kg ya pieces?";
        return "“" + name + "” catalog mein nahi hai. Kya ise naya product banaun, jiska stock “" + unit + "” mein ginen? Haan bolein, ya saved product ka naam batayein. Quantity aur godown yaad hain.";
    }

    private InterpretationOutcome answerQuestion(UUID userId, String source, LocalDate day, Instant at) {
        var state = dialogue.get(userId).orElse(null);
        UUID focused = focusedDraft(userId);
        if (state == null || focused == null) return null;
        String words = cleanWords(source);
        var current = drafts.inspect(userId, focused);
        DraftProposal proposal = new DraftProposal(current.getId(), current.getVersion(), current.getContentHash(), current.getStatus(),
                current.getMissingFields().isBlank() ? List.of() : List.of(current.getMissingFields().split(",")), current.getAmountPaise());
        InterpretationOutcome same = new InterpretationOutcome(proposal, List.of(), null, null);
        if (state.question() == null) {
            if (isApproval(source) && current.getStatus() == in.munimsaathi.draft.DraftStatus.NEEDS_CLARIFICATION) {
                return withAnswer(same, "Is draft mein abhi " + current.getMissingFields() + " clear karna hai. Pehle yeh detail bataiye; entry post nahi hui.");
            }
            return null;
        }
        if (current.getVersion() != state.version()) {
            dialogue.clear(userId);
            return finish(userId, withAnswer(same, "Is draft ki detail badal gayi hai. Naya draft dekh kar confirm karein."));
        }
        boolean hasYes = words.matches(".*(?:^| )(?:haan|han|ha|yes|हाँ|हां)(?: |$).*");
        boolean hasNo = words.matches(".*(?:^| )(?:nahi|nahin|no|नहीं|नही)(?: |$).*");
        if (hasYes && hasNo) {
            return withAnswer(same, "CREATE_PARTY".equals(state.question())
                    ? "Kya nayi party banani hai ya purani party hai? ‘Haan, nayi party banao’ ya ‘Nahi, purani party hai’ bataiye. Entry abhi post nahi hui."
                    : "Naya product banana hai ya purana product hai? ‘Haan, naya banao’ ya ‘Nahi, purana hai’ bataiye. Entry abhi post nahi hui.");
        }
        if (Set.of("no", "nahi", "nahin", "नहीं", "नही").contains(words)) {
            String next = state.question().contains("PARTY") ? "PARTY_NAME" : "PRODUCT_NAME";
            dialogue.save(userId, focused, next, state.name(), state.unit());
            return withAnswer(same, "Theek hai, naya record nahi banaunga. Purani " + ("PARTY_NAME".equals(next) ? "party" : "product") + " ka saved naam bataiye; baaki entry yaad hai.");
        }
        if ("PRODUCT_UNIT".equals(state.question()) && Set.of("bori", "बोरी", "kg", "किलो", "piece", "pieces", "पीस", "box", "बॉक्स").contains(words)) {
            dialogue.save(userId, focused, "CREATE_PRODUCT", state.name(), words);
            return withAnswer(same, questionForDraft(userId, focused, "CREATE_PRODUCT", state.name(), words));
        }
        if ("STOCK_ACTION".equals(state.question())) {
            String action = switch (words) {
                case "maal aaya", "naya maal aaya", "received", "purchase", "माल आया", "नया माल आया", "माल आया है" -> "goods_received";
                case "maal bika", "sale", "bika", "माल बिका", "बिक्री हुई" -> "sale";
                case "sirf jagah", "location", "sirf location", "सिर्फ जगह", "जगह नोट करनी है" -> "remember_location";
                default -> null;
            };
            if (action != null) {
                var fields = mapper.createObjectNode();
                fields.put("turn", "answer_to_clarification"); fields.put("target_draft_id", focused.toString()); fields.put("action", action);
                if (current.getHeardParty() != null) fields.put("party_name", current.getHeardParty());
                if (current.getHeardProduct() != null) fields.put("product_name", current.getHeardProduct());
                if (current.getHeardLocation() != null) fields.put("location_code", current.getHeardLocation());
                if ("remember_location".equals(action)) fields.put("location_label", current.getHeardProduct() == null ? null
                        : (current.getHeardParty() == null ? "" : current.getHeardParty() + " ka ") + current.getHeardProduct());
                dialogue.clear(userId);
                return finish(userId, rememberHeard(userId, fields, apply(userId, focused, fields, day, at)));
            }
            if (isApproval(source)) return withAnswer(same, "Sirf Haan se action clear nahi hai. Maal aaya, bika, ya sirf jagah note karni hai?");
        }
        String withoutPrefix = words.replaceFirst("^(?:product|party|maal|प्रोडक्ट|पार्टी) +", "");
        boolean namedMatch = "MATCH_PARTY".equals(state.question()) && state.name() != null && cleanWords(state.name()).equals(withoutPrefix);
        if (!namedMatch && state.name() != null && cleanWords(state.name()).equals(withoutPrefix)) {
            String question = "PARTY_NAME".equals(state.question()) ? "CREATE_PARTY"
                    : "PRODUCT_NAME".equals(state.question()) ? (state.unit() == null ? "PRODUCT_UNIT" : "CREATE_PRODUCT") : state.question();
            dialogue.save(userId, focused, question, state.name(), state.unit());
            return withAnswer(same, questionForDraft(userId, focused, question, state.name(), state.unit()));
        }
        boolean yes = namedMatch || Set.of("yes", "y", "haan", "han", "ha", "हाँ", "हां", "haan ji", "हाँ जी", "हां जी", "haan bana do", "haan jod do", "bana do", "jod do", "हाँ बना दो", "हाँ जोड़ दो", "haan nayi party banao", "haan naya product banao", "हाँ नई पार्टी बनाओ", "हाँ नया प्रोडक्ट बनाओ", "theek hai", "ठीक है").contains(words);
        if (!yes && hasYes && !hasNo && state.name() != null) {
            String name = cleanWords(state.name());
            yes = Set.of("haan nayi party hai " + name, "हां नई पार्टी है " + name, "हाँ नई पार्टी है " + name,
                    "haan naya product hai " + name, "हाँ नया प्रोडक्ट है " + name).contains(words);
        }
        if (!yes) return null;
        if (!"CREATE_PARTY".equals(state.question()) && !"CREATE_PRODUCT".equals(state.question()) && !"MATCH_PARTY".equals(state.question()) && !"USE_PARTY".equals(state.question())) {
            return withAnswer(same, "Pehle " + ("PRODUCT_UNIT".equals(state.question()) ? "base unit" : "saved naam") + " bataiye; Haan se yeh detail nahi milti.");
        }
        var fields = mapper.createObjectNode();
        fields.put("turn", "answer_to_clarification");
        fields.put("target_draft_id", focused.toString());
        if ("CREATE_PARTY".equals(state.question()) || "MATCH_PARTY".equals(state.question()) || "USE_PARTY".equals(state.question())) {
            if ("USE_PARTY".equals(state.question())) {
                var named = parties.findByName(userId, state.name());
                if (named.size() != 1) return withAnswer(same, "Party ab ambiguous hai. Poora saved naam batayein.");
                parties.changeRole(userId, named.get(0).getId(), PartyRole.BOTH);
            }
            fields.put("party_name", state.name());
            if ("CREATE_PARTY".equals(state.question())) fields.put("create_party", true);
        } else {
            fields.put("product_name", state.name()); fields.put("unit", state.unit()); fields.put("create_product", true);
        }
        dialogue.clear(userId);
        return finish(userId, rememberHeard(userId, fields, apply(userId, focused, fields, day, at)));
    }

    private JsonNode prepareTurn(UUID userId, String source, JsonNode reply) {
        var fields = (com.fasterxml.jackson.databind.node.ObjectNode) reply.deepCopy();
        String words = cleanWords(source);
        boolean storage = words.matches(".*(?:rakha|rakhe|pada|रखा|रखे|पड़ा|पड़ा).*")
                && words.matches(".*(?:godown|godam|गोदाम|गोडाउन).*");
        boolean arrival = words.matches(".*(?:^| )(?:liya|kharida|aaya|received|purchase|bought|लिया|खरीदा|आया|आए)(?: |$).*");
        boolean query = words.matches(".*(?:^| )(?:kahan|kahaan|कहाँ|कहां|where)(?: |$).*");
        if (storage && !arrival && !query && !"answer_to_clarification".equals(text(fields, "turn"))
                && !source.split("(?iu)godown|godam|गोदाम|गोडाउन", 2)[0].isBlank()) {
            String label = text(fields, "location_label");
            if (label == null) {
                label = source.split("(?iu)godown|godam|गोदाम|गोडाउन", 2)[0].strip();
                label = label.replaceAll("[\\p{P}\\p{Cf}]+$", "").trim();
            }
            fields.put("action", "remember_location"); fields.put("turn", "new_action"); fields.put("location_label", label);
            fields.remove(List.of("lines", "create_party", "create_product", "target_draft_id"));
        }
        UUID focused = focusedDraft(userId);
        if (focused != null && "LOCATION".equals(drafts.kindOf(userId, focused))
                && "answer_to_clarification".equals(text(fields, "turn"))
                && firstText(fields, "action", "intent") == null
                && (fields.hasNonNull("product_name") || fields.hasNonNull("quantity"))) {
            var note = drafts.inspect(userId, focused);
            if (!fields.hasNonNull("location_code") && note.getLocationId() != null) {
                godowns.findById(note.getLocationId()).ifPresent(place -> fields.put("location_code", place.getCode()));
            }
            if (!fields.hasNonNull("party_name") && note.getHeardParty() != null) fields.put("party_name", note.getHeardParty());
            drafts.suspend(userId, focused);
            fields.put("turn", "new_action"); fields.remove("target_draft_id");
        }
        String turn = text(fields, "turn");
        if ("new_action".equals(turn) || "read_query".equals(turn)) dialogue.clear(userId);
        return fields;
    }

    public static boolean isApproval(String source) {
        return source != null && Set.of("yes", "y", "haan", "han", "ha", "haan ji", "han ji", "हाँ", "हां", "हाँ जी", "हां जी").contains(cleanWords(source));
    }

    private static String cleanWords(String source) {
        return source.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Cf}]", " ").trim().replaceAll("\\s+", " ");
    }

    private InterpretationOutcome rememberHeard(UUID userId, JsonNode reply, InterpretationOutcome outcome) {
        if (outcome.draft() != null) {
            drafts.rememberHeard(userId, outcome.draft().id(), text(reply, "party_name"), text(reply, "product_name"), text(reply, "location_code"));
        }
        return outcome;
    }

    private InterpretationOutcome apply(
            UUID userId,
            UUID draftId,
            JsonNode reply,
            LocalDate occurredOn,
            Instant occurredAt
    ) {
        List<String> refused = refusedTools(reply);
        String turn = text(reply, "turn");
        if ("cancel".equals(turn)) {
            dialogue.clear(userId);
            boolean pendingQuestion = questions.get(userId).isPresent();
            questions.clear(userId);
            if (pendingQuestion && draftId == null && text(reply, "target_draft_id") == null) {
                return new InterpretationOutcome(null, refused, null, "Cancelled the balance question. Nothing was posted.");
            }
            return new InterpretationOutcome(null, refused, null, cancelDraft(userId, draftId, text(reply, "target_draft_id")));
        }
        String target = text(reply, "target_draft_id");
        if (target != null && ("answer_to_clarification".equals(turn) || "edit_action".equals(turn))) {
            UUID selected;
            try { selected = UUID.fromString(target); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Sahi pending entry chuniye."); }
            boolean allowed = drafts.open(userId).stream().anyMatch(open -> open.id().equals(selected));
            if (!allowed) throw new IllegalArgumentException("Yeh entry is business mein pending nahi hai.");
            draftId = selected;
        }
        var pendingPurpose = questions.get(userId).orElse(null);
        String causeAnswer = text(reply, "cause");
        String proposedAction = firstText(reply, "action", "intent");
        boolean purposeReply = "answer_to_clarification".equals(turn) && pendingPurpose != null
                && !"both".equals(pendingPurpose.side()) && pendingPurpose.partyName() != null && pendingPurpose.amountPaise() != null
                && ("new".equals(causeAnswer) || "existing".equals(causeAnswer))
                && (proposedAction == null || "supplier_payable".equals(proposedAction) || "standalone_receivable".equals(proposedAction));
        if (purposeReply) {
            var continued = reply.deepCopy();
            var fields = (com.fasterxml.jackson.databind.node.ObjectNode) continued;
            fields.put("action", "payable".equals(pendingPurpose.side()) ? "supplier_payable" : "standalone_receivable");
            if (text(reply, "party_name") == null) fields.put("party_name", pendingPurpose.partyName());
            if (amount(reply.get("amount_paise")) == null) fields.put("amount_paise", pendingPurpose.amountPaise());
            reply = fields;
            questions.clear(userId);
        }
        UUID ewayDraftId = ewayDraft(userId, reply);
        String intent = firstText(reply, "action", "intent");
        String normalized = intent == null ? "" : intent.toLowerCase(Locale.ROOT);
        String action = switch (normalized) {
            case "sale" -> "sale";
            case "receive", "purchase", "goods_received" -> "receive";
            case "customer_payment_received", "payment_received", "payment" -> "payment";
            case "standalone_receivable", "receivable" -> "receivable";
            case "supplier_payable", "payable" -> "payable";
            case "outgoing_payment", "money_paid", "supplier_payment" -> "outgoing_payment";
            default -> null;
        };
        if ("remember_location".equals(normalized)) {
            UUID selected = draftId;
            if (selected == null && "answer_to_clarification".equals(turn)) selected = focusedDraft(userId);
            if (selected != null && drafts.kindOf(userId, selected) != null && !"LOCATION".equals(drafts.kindOf(userId, selected))) {
                if ("edit_action".equals(turn)) drafts.suspend(userId, selected);
                selected = null;
            }
            return new InterpretationOutcome(drafts.proposeLocation(userId, selected, text(reply, "location_label"),
                    location(userId, text(reply, "location_code")), occurredOn, occurredAt), refused, ewayDraftId, null);
        }
        if (action == null && normalized.isBlank() && "answer_to_clarification".equals(turn)) {
            UUID note = draftId == null ? focusedDraft(userId) : draftId;
            if (note != null && "LOCATION".equals(drafts.kindOf(userId, note))) {
                return new InterpretationOutcome(drafts.proposeLocation(userId, note, text(reply, "location_label"),
                        location(userId, text(reply, "location_code")), occurredOn, occurredAt), refused, ewayDraftId, null);
            }
        }
        if (("locate_goods".equals(normalized) || "locate".equals(normalized)) && text(reply, "location_label") != null) {
            var places = locations.locateLabel(userId, text(reply, "location_label"));
            if (places.isEmpty()) {
                UUID knownProduct = product(userId, firstText(reply, "product_name", "location_label"));
                if (knownProduct != null) {
                    var stockPlaces = locations.locate(userId, knownProduct);
                    String stockAnswer = stockPlaces.isEmpty() ? "Is product ki jagah abhi record mein nahi hai."
                            : stockPlaces.stream().map(place -> godowns.findById(place.locationId()).map(Godown::getName).orElse("Unknown godown")
                            + (place.currentlyStocked() ? " mein stock hai." : " mein last note kiya tha; abhi stock confirm nahi hai."))
                            .collect(java.util.stream.Collectors.joining(" "));
                    return new InterpretationOutcome(null, refused, ewayDraftId, stockAnswer);
                }
            }
            String answer = places.isEmpty() ? "Is label ki jagah abhi record mein nahi hai."
                    : places.stream().map(place -> godowns.findById(place.locationId()).map(Godown::getName).orElse("Unknown godown"))
                    .collect(java.util.stream.Collectors.joining(", ")) + " mein last note kiya tha. Stock ki quantity is note se confirm nahi hoti.";
            return new InterpretationOutcome(null, refused, ewayDraftId, answer);
        }
        boolean balanceQuery = "party_balance".equals(normalized) || "balance".equals(normalized)
                || "confirm_existing_debt".equals(normalized);
        var pending = questions.get(userId).orElse(null);
        boolean partyAnswer = pending != null && action == null && normalized.isBlank()
                && (text(reply, "party_name") != null || text(reply, "balance_side") != null || Boolean.TRUE.equals(flag(reply, "create_party")))
                && !"new_action".equals(turn);
        if (balanceQuery || partyAnswer) {
            String side = text(reply, "balance_side");
            if (!"payable".equals(side) && !"receivable".equals(side) && !"both".equals(side)) {
                side = pending == null ? "both" : pending.side();
            }
            String name = text(reply, "party_name");
            if (name == null && pending != null && !"new_action".equals(turn)) {
                name = pending.partyName();
            }
            Long statedAmount = amount(reply.get("amount_paise"));
            if (statedAmount == null && pending != null && !"new_action".equals(turn)) {
                statedAmount = pending.amountPaise();
            }
            return new InterpretationOutcome(null, refused, ewayDraftId,
                    balanceAnswer(userId, name, side, statedAmount, Boolean.TRUE.equals(flag(reply, "create_party"))));
        }
        if (action != null && !"answer_to_clarification".equals(turn)) {
            questions.clear(userId);
        }
        if ("answer_to_clarification".equals(turn) && draftId == null) {
            UUID focused = focusedDraft(userId);
            if (focused != null && (action == null || kindOf(action).equals(drafts.kindOf(userId, focused)))) draftId = focused;
        }
        if ("answer_to_clarification".equals(turn) && draftId == null) {
            var candidates = drafts.open(userId).stream()
                    .filter(open -> action == null || kindOf(action).equals(open.kind())).toList();
            if (candidates.size() == 1) draftId = candidates.get(0).id();
            else if (candidates.size() > 1) {
                return new InterpretationOutcome(null, refused, ewayDraftId,
                        "Kaunsi pending entry ki baat hai? Entry ID ke saath batayein: "
                                + candidates.stream().map(row -> row.id().toString()).collect(java.util.stream.Collectors.joining(", ")));
            }
        }
        boolean supplier = "receive".equals(action) || "payable".equals(action);
        if (action == null && "answer_to_clarification".equals(turn)) {
            var open = drafts.open(userId);
            UUID selectedId = draftId;
            var selected = open.stream().filter(row -> selectedId == null || row.id().equals(selectedId)).toList();
            if (selected.size() == 1) {
                supplier = "RECEIPT".equals(selected.get(0).kind()) || "PAYABLE".equals(selected.get(0).kind());
            }
        }
        UUID customerId;
        boolean outgoing = "outgoing_payment".equals(action) || (action == null &&
                (draftId != null ? "OUTGOING_PAYMENT".equals(drafts.kindOf(userId, draftId))
                        : drafts.open(userId).size() == 1 && "OUTGOING_PAYMENT".equals(drafts.open(userId).get(0).kind())));
        if (outgoing && text(reply, "party_name") != null) {
            var named = parties.findByName(userId, text(reply, "party_name"));
            customerId = named.size() == 1 ? named.get(0).getId() : null;
        } else {
            customerId = party(userId, text(reply, "party_name"), supplier);
        }
        if (customerId == null && text(reply, "party_name") == null && ("payment".equals(action) || "receivable".equals(action))) {
            List<Party> owing = parties.list(userId).stream()
                    .filter(party -> party.getRole() == PartyRole.CUSTOMER || party.getRole() == PartyRole.BOTH)
                    .filter(party -> posting.customerReceivablePaise(userId, party.getId()) > 0)
                    .toList();
            if (owing.size() == 1) {
                customerId = owing.get(0).getId();
            }
        }
        UUID productId = product(userId, text(reply, "product_name"));
        UUID locationId = location(userId, text(reply, "location_code"));
        BigDecimal quantity = quantity(reply.get("quantity"));
        String unit = text(reply, "unit");
        Long amount = amount(reply.get("amount_paise"));
        boolean money = "payment".equals(action) || "receivable".equals(action) || "payable".equals(action) || outgoing;
        JsonNode lineNodes = reply.get("lines");
        if (!money && lineNodes != null && lineNodes.isArray() && !lineNodes.isEmpty()) {
            List<DraftLineSpec> specs = new ArrayList<>();
            for (JsonNode node : lineNodes) {
                specs.add(lineSpec(userId, node));
            }
            UUID lineTarget = bindDraft(userId, draftId, turn, action, customerId, productId, locationId, quantity, unit, amount, null);
            if (draftId != null && action != null) {
                String openKind = drafts.kindOf(userId, draftId);
                String nextKind = kindOf(action);
                if (openKind != null && nextKind != null && !openKind.equals(nextKind)) {
                    drafts.suspend(userId, draftId);
                    lineTarget = null;
                }
            }
            DraftProposal draft = lineTarget == null
                    ? drafts.proposeLines(userId, action, customerId, locationId, specs, occurredOn, occurredAt)
                    : drafts.replaceLines(userId, lineTarget, action, customerId, locationId, specs, occurredOn, occurredAt);
            return new InterpretationOutcome(draft, refused, ewayDraftId, null);
        }
        if ("locate".equals(normalized) || "locate_goods".equals(normalized)) {
            return new InterpretationOutcome(null, refused, ewayDraftId, answerFor(userId, "locate", customerId, productId, reply));
        }
        String answer = answerFor(userId, normalized, customerId, productId, reply);
        if (answer != null) {
            return new InterpretationOutcome(null, refused, ewayDraftId, answer);
        }
        String method = text(reply, "payment_method");
        String purpose = text(reply, "payment_purpose");
        if (purpose == null && outgoing && draftId != null) purpose = drafts.inspect(userId, draftId).getCause();
        if ("outgoing_payment".equals(action) && "edit_action".equals(turn)
                && drafts.open(userId).stream().noneMatch(d -> "OUTGOING_PAYMENT".equals(d.kind()))) {
            UUID namedParty = customerId;
            var candidates = drafts.open(userId).stream()
                    .filter(d -> money(d.kind()) && !"OUTGOING_PAYMENT".equals(d.kind()))
                    .filter(d -> namedParty == null || namedParty.equals(d.partyId())).toList();
            if (candidates.size() != 1) {
                return new InterpretationOutcome(null, refused, ewayDraftId, "Kaunsi payment ki baat kar rahe hain? Party aur amount confirm kar dein.");
            }
            var previous = candidates.get(0);
            if (amount == null) amount = previous.amountPaise();
            if (customerId == null) customerId = previous.partyId();
            drafts.suspend(userId, previous.id());
            draftId = null;
        }
        boolean stated = action != null || customerId != null || productId != null || locationId != null
                || quantity != null || unit != null || amount != null || method != null || purpose != null
                || Boolean.TRUE.equals(flag(reply, "create_party")) || Boolean.TRUE.equals(flag(reply, "create_product"))
                || ("answer_to_clarification".equals(turn) && (text(reply, "party_name") != null || text(reply, "product_name") != null));
        if (!stated) {
            return new InterpretationOutcome(null, refused, ewayDraftId, spoken(reply));
        }
        if (("receivable".equals(action) || "payable".equals(action))
                && "existing".equalsIgnoreCase(text(reply, "cause")) && customerId != null && amount != null) {
            long owed = "payable".equals(action) ? posting.supplierPayablePaise(userId, customerId)
                    : posting.customerReceivablePaise(userId, customerId);
            if (owed == amount) {
                return new InterpretationOutcome(null, refused, ewayDraftId, "That amount is already recorded. I will not add it again.");
            }
            return new InterpretationOutcome(null, refused, ewayDraftId, "That amount is not the balance I have. Say the new amount if it is new.");
        }
        if (Boolean.TRUE.equals(flag(reply, "create_party")) && customerId == null && text(reply, "party_name") != null) {
            PartyRole role = outgoing ? ("loan".equals(purpose) ? PartyRole.CUSTOMER : PartyRole.SUPPLIER) : supplier ? PartyRole.SUPPLIER : PartyRole.CUSTOMER;
            customerId = parties.create(userId, text(reply, "party_name"), role).getId();
        }
        if (Boolean.TRUE.equals(flag(reply, "create_product")) && productId == null && text(reply, "product_name") != null) {
            String selectedKind = draftId == null ? null : drafts.kindOf(userId, draftId);
            if (!("receive".equals(action) || "sale".equals(action)
                    || (action == null && ("RECEIPT".equals(selectedKind) || "SALE".equals(selectedKind))))) {
                return new InterpretationOutcome(null, refused, ewayDraftId, "Naya product jodne ke liye maal ki pending entry chuniye.");
            }
            if (unit == null) {
                return new InterpretationOutcome(null, refused, ewayDraftId, "Naya product kis base unit mein rakhein—bori, kg ya pieces? Unit bataiye.");
            }
            productId = catalog.registerProduct(userId, "WA-" + UUID.randomUUID(), text(reply, "product_name"),
                    null, null, unit, false).getId();
        }
        boolean locationOnly = action == null && locationId != null && quantity == null && unit == null
                && amount == null && customerId == null && productId == null && method == null;
        if (locationOnly) {
            long needing = drafts.open(userId).stream()
                    .filter(draft -> draft.missing() != null && draft.missing().contains("location"))
                    .count();
            if (needing > 1) {
                return new InterpretationOutcome(null, refused, ewayDraftId, "Which draft should get that godown?");
            }
        }
        String bindTurn = turn;
        var pendingMoney = drafts.open(userId);
        if (action == null && pendingMoney.size() == 1 && money(pendingMoney.get(0).kind())
                && productId == null && locationId == null && quantity == null && unit == null) {
            var previous = pendingMoney.get(0);
            if ((customerId != null && previous.missing().contains("party") && amount == null && method == null && purpose == null)
                    || (amount != null && (previous.missing().contains("amount") || amount.equals(previous.amountPaise())))
                    || (method != null && previous.missing().contains("payment_method"))
                    || (purpose != null && previous.missing().contains("payment_purpose"))) {
                bindTurn = "answer_to_clarification";
            }
        }
        UUID bound = bindDraft(userId, draftId, bindTurn, action, customerId, productId, locationId, quantity, unit, amount, method);
        if (bound == null && action == null && amount != null && productId == null && locationId == null && quantity == null && unit == null) {
            return new InterpretationOutcome(null, refused, ewayDraftId, "₹" + BigDecimal.valueOf(amount, 2).toPlainString()
                    + " ki baat hai. Paise mile hain, diye hain, ya lene/dene baaki hain?");
        }
        if (bound != null) {
            drafts.forgetResolved(userId, bound, text(reply, "party_name") != null && customerId == null,
                    text(reply, "product_name") != null && productId == null,
                    text(reply, "location_code") != null && locationId == null);
        }
        String boundKind = bound == null ? null : drafts.kindOf(userId, bound);
        boolean moneyDraft = money || "PAYMENT".equals(boundKind) || "RECEIVABLE".equals(boundKind) || "PAYABLE".equals(boundKind) || "OUTGOING_PAYMENT".equals(boundKind);
        if (moneyDraft) {
            String moneyAction = action != null ? action : switch (boundKind) {
                case "RECEIVABLE" -> "receivable";
                case "PAYABLE" -> "payable";
                case "OUTGOING_PAYMENT" -> "outgoing_payment";
                default -> "payment";
            };
            UUID allocates = null;
            if ("payment".equals(moneyAction) && customerId != null && amount != null && flag(reply, "allocate") != Boolean.FALSE) {
                allocates = posting.soleOpenSale(userId, customerId, amount).orElse(null);
            }
            String direction = "outgoing_payment".equals(moneyAction) ? "outgoing" : "payable".equals(moneyAction) ? "payable" : "receivable";
            String why = "outgoing_payment".equals(moneyAction) ? purpose : text(reply, "cause");
            DraftProposal draft = bound == null
                    ? drafts.proposeMoney(userId, moneyAction, customerId, amount, method, allocates, why, direction, occurredOn, occurredAt)
                    : drafts.continueMoney(userId, bound, moneyAction, customerId, amount, method, allocates, why, direction, occurredOn, occurredAt);
            String note = clarification(userId, text(reply, "party_name"), customerId, supplier, null, productId);
            if (customerId == null && text(reply, "party_name") == null) {
                note = join(note, "Who was this?");
            }
            if (customerId == null && text(reply, "party_name") != null) {
                note = join(note, "Say create if I should add this party.");
            }
            if ("outgoing_payment".equals(moneyAction) && (customerId == null || draft.missingFields().contains("amount"))) {
                return new InterpretationOutcome(draft, refused, ewayDraftId, note);
            }
            if (draft.missingFields().contains("payment_purpose")) {
                note = join(note, "Purane bill ka bakaya chukaya, advance diya, ya udhaar diya? Amount dobara batane ki zarurat nahi hai.");
            } else if (draft.missingFields().contains("payment_method")) {
                note = join(note, "Cash or UPI?");
            }
            if (draft.missingFields().contains("cause")) {
                note = join(note, "payable".equals(moneyAction)
                        ? "Is this a new amount you owe, or an existing balance?"
                        : "Is this a new amount you gave, or an existing balance?");
            }
            return new InterpretationOutcome(draft, refused, ewayDraftId, note);
        }
        DraftProposal draft = bound == null
                ? drafts.propose(userId, action, customerId, productId, locationId, quantity, unit, amount, occurredOn, occurredAt)
                : drafts.continueDraft(userId, bound, action, customerId, productId, locationId, quantity, unit, amount, occurredOn, occurredAt);
        return new InterpretationOutcome(
                draft,
                refused,
                ewayDraftId,
                clarification(userId, text(reply, "party_name"), customerId, supplier, text(reply, "product_name"), productId)
        );
    }

    private String balanceAnswer(UUID userId, String name, String side, Long statedAmount, boolean createRequested) {
        if (name == null) {
            questions.save(userId, null, side, statedAmount);
            return "Which party is this for? I have kept your balance question.";
        }
        List<Party> matches = parties.findByName(userId, name);
        if (matches.isEmpty() && createRequested && !"both".equals(side)) {
            Party created = parties.create(userId, name, "payable".equals(side) ? PartyRole.SUPPLIER : PartyRole.CUSTOMER);
            matches = List.of(created);
        }
        if (matches.size() != 1) {
            questions.save(userId, name, side, statedAmount);
            if (matches.isEmpty()) {
                return "I could not find a saved party named " + name
                        + ". Give the saved full name, or explicitly ask me to create this "
                        + ("payable".equals(side) ? "supplier" : "receivable".equals(side) ? "customer" : "party with its customer/supplier role")
                        + ". Your balance question is still open; no debt was posted.";
            }
            return "More than one party is named " + name + ". Which saved party do you mean?";
        }
        Party party = matches.get(0);
        boolean customer = party.getRole() == PartyRole.CUSTOMER || party.getRole() == PartyRole.BOTH;
        boolean supplier = party.getRole() == PartyRole.SUPPLIER || party.getRole() == PartyRole.BOTH;
        if (("payable".equals(side) && !supplier) || ("receivable".equals(side) && !customer)) {
            questions.save(userId, name, side, statedAmount);
            return name + " is saved as a " + (supplier ? "supplier" : "customer")
                    + ". Did you mean money you owe them (payable), or money they owe you (receivable)? Nothing was posted.";
        }
        if ("both".equals(side) && customer != supplier) {
            side = supplier ? "payable" : "receivable";
        }
        questions.clear(userId);
        StringBuilder answer = new StringBuilder(party.getDisplayName()).append(": ");
        Long balance = null;
        if (!"payable".equals(side) && customer) {
            balance = posting.customerReceivablePaise(userId, party.getId());
            answer.append("Receivable is ₹").append(BigDecimal.valueOf(balance, 2).toPlainString()).append(" (they owe you).");
        }
        if (!"receivable".equals(side) && supplier) {
            long payable = posting.supplierPayablePaise(userId, party.getId());
            if (balance != null) answer.append(' ');
            answer.append("Payable is ₹").append(BigDecimal.valueOf(payable, 2).toPlainString()).append(" (you owe them).");
            balance = balance == null ? payable : null;
        }
        if (statedAmount != null && balance != null) {
            if (statedAmount.equals(balance)) answer.append(" That amount is already recorded; no new debt was added.");
            else answer.append(" You mentioned ₹").append(BigDecimal.valueOf(statedAmount, 2).toPlainString())
                    .append("; that differs from the recorded balance. Is this a new amount to record, or an existing balance? Nothing was added or changed.");
            if (!statedAmount.equals(balance) && !"both".equals(side)) {
                questions.save(userId, party.getDisplayName(), side, statedAmount);
            }
        }
        return answer.toString();
    }

    private String cancelDraft(UUID userId, UUID draftId, String targetId) {
        List<DraftBook.OpenDraft> open = drafts.open(userId);
        UUID selected = draftId;
        if (targetId != null) {
            try {
                selected = UUID.fromString(targetId);
            } catch (IllegalArgumentException exception) {
                return "Which draft should I cancel?";
            }
        }
        if (selected == null) {
            if (open.isEmpty()) {
                return "There is no open draft to cancel.";
            }
            if (open.size() != 1) {
                return "Which draft should I cancel?";
            }
            selected = open.get(0).id();
        }
        UUID target = selected;
        if (open.stream().noneMatch(draft -> target.equals(draft.id()))) {
            return "That draft is not open in this business. Nothing was canceled.";
        }
        drafts.cancel(userId, target);
        return "Cancelled. Nothing was posted.";
    }

    private JsonNode commandJson(String modelText) {
        if (modelText == null || modelText.isBlank()) {
            throw new IllegalArgumentException("The model reply was empty.");
        }
        String trimmed = modelText.trim();
        JsonNode parsed = tryObject(trimmed);
        if (parsed == null) {
            parsed = tryObject(stripFence(trimmed));
        }
        if (parsed == null) {
            parsed = tryObject(objectSlice(trimmed));
        }
        return parsed;
    }

    private JsonNode tryObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, lastFence).trim();
    }

    private static String objectSlice(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String answerFor(UUID userId, String intent, UUID partyId, UUID productId, JsonNode reply) {
        if ("balance".equals(intent)) {
            if (partyId == null) {
                return "Which party? I will not guess the balance.";
            }
            long paise = posting.customerReceivablePaise(userId, partyId);
            return "Receivable is ₹" + BigDecimal.valueOf(paise, 2).toPlainString() + ".";
        }
        if ("locate".equals(intent)) {
            if (productId == null) {
                return "Which product? I will not guess the godown.";
            }
            List<Place> places = locations.locate(userId, productId);
            if (places.isEmpty()) {
                return "No location is remembered for that product.";
            }
            StringBuilder text = new StringBuilder();
            for (Place place : places) {
                Godown godown = godowns.findById(place.locationId()).orElse(null);
                String name = godown == null ? "unknown godown" : godown.getName();
                text.append(name).append(place.currentlyStocked() ? " has stock. " : " was last noted. ");
            }
            return text.toString().trim();
        }
        if ("reminder".equals(intent)) {
            LocalDate date = localDate(text(reply, "reminder_date"));
            LocalTime time = localTime(text(reply, "reminder_time"));
            if (date == null || time == null) {
                return "Ask for the sending time.";
            }
            UUID taskId = reminders.propose(
                    userId,
                    ReminderKind.OWNER_REMINDER,
                    partyId,
                    null,
                    null,
                    date,
                    time,
                    "owner reminder",
                    false,
                    null,
                    true
            );
            return "Reminder " + taskId + " is waiting for approval. It is not sent yet.";
        }
        return null;
    }

    private DraftLineSpec lineSpec(UUID userId, JsonNode node) {
        UUID productId = product(userId, text(node, "product_name"));
        BigDecimal lineQuantity = quantity(node.get("quantity"));
        String unit = text(node, "unit");
        Long lineAmount = amount(node.get("amount_paise"));
        String unresolved = null;
        if (productId == null) {
            unresolved = "product";
        } else if (lineQuantity == null || lineQuantity.signum() <= 0) {
            unresolved = "quantity";
        } else if (unit == null) {
            unresolved = "unit";
        } else if (!catalog.canConvert(userId, productId, lineQuantity, unit)) {
            unresolved = "unit";
        }
        return new DraftLineSpec(productId, lineQuantity, unit, lineAmount, unresolved);
    }

    private List<String> refusedTools(JsonNode reply) {
        List<String> refused = new ArrayList<>();
        JsonNode tools = reply.get("requested_tools");
        if (tools == null || !tools.isArray()) {
            return List.of();
        }
        for (JsonNode tool : tools) {
            String name = tool.asText();
            if ("prepare_eway_bill".equals(name)) {
                continue;
            }
            if (!READ_TOOLS.contains(name)) {
                refused.add(name);
            }
        }
        return List.copyOf(refused);
    }

    private UUID ewayDraft(UUID userId, JsonNode reply) {
        if (!requested(reply, "prepare_eway_bill")) {
            return null;
        }
        EwayDetails details = ewayDetails(reply);
        String invoiceId = text(reply, "invoice_id");
        if (invoiceId != null) {
            try {
                return eway.prepare(userId, UUID.fromString(invoiceId), details);
            } catch (IllegalArgumentException exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("Invalid UUID")) {
                    throw new IllegalArgumentException("Ask which invoice the e-way bill is for.");
                }
                throw exception;
            }
        }
        Long number = amount(reply.get("invoice_number"));
        if (number == null) {
            throw new IllegalArgumentException("Ask which invoice the e-way bill is for.");
        }
        return eway.prepareNumber(userId, number, details);
    }

    private EwayDetails ewayDetails(JsonNode reply) {
        return new EwayDetails(
                text(reply, "supply_type"),
                text(reply, "sub_supply_type"),
                ewayParty(reply, "from"),
                ewayParty(reply, "to"),
                integer(reply, "transaction_type"),
                text(reply, "distance"),
                text(reply, "trans_mode"),
                text(reply, "vehicle_no"),
                text(reply, "transporter_id"),
                amount(reply.get("hsn_code")),
                text(reply, "qty_unit"),
                new EwayTax(
                        amount(reply.get("taxable_paise")),
                        amount(reply.get("cgst_paise")),
                        amount(reply.get("sgst_paise")),
                        amount(reply.get("igst_paise")),
                        amount(reply.get("cess_paise")),
                        quantity(reply.get("cgst_rate")),
                        quantity(reply.get("sgst_rate")),
                        quantity(reply.get("igst_rate")),
                        quantity(reply.get("cess_rate"))
                )
        );
    }

    private EwayParty ewayParty(JsonNode reply, String prefix) {
        return new EwayParty(
                text(reply, prefix + "_gstin"),
                text(reply, prefix + "_name"),
                text(reply, prefix + "_address"),
                text(reply, prefix + "_place"),
                integer(reply, prefix + "_pincode"),
                integer(reply, prefix + "_state_code"),
                integer(reply, prefix + "_actual_state_code")
        );
    }

    private boolean requested(JsonNode reply, String tool) {
        JsonNode tools = reply.get("requested_tools");
        if (tools == null || !tools.isArray()) {
            return false;
        }
        for (JsonNode node : tools) {
            if (tool.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private static Integer integer(JsonNode reply, String field) {
        Long value = amount(reply.get(field));
        if (value == null || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private static String spoken(JsonNode reply) {
        String replyText = text(reply, "reply");
        return replyText == null ? "Namaste. Tell me what was received, sold, or paid." : replyText;
    }

    private String clarification(
            UUID userId,
            String partyName,
            UUID partyId,
            boolean supplier,
            String productName,
            UUID productId
    ) {
        StringBuilder note = new StringBuilder();
        if (partyName != null && partyId == null) {
            note.append(partyNote(userId, partyName, supplier)).append(' ');
        }
        if (productName != null && productId == null) {
            note.append("I heard ").append(productName).append(", and no saved product has that name.");
        }
        return note.isEmpty() ? null : note.toString().trim();
    }

    private String partyNote(UUID userId, String partyName, boolean supplier) {
        List<Party> named = parties.findByName(userId, partyName);
        if (named.size() == 1) {
            Party party = named.get(0);
            String role = party.getRole() == PartyRole.CUSTOMER ? "a customer" : "a supplier";
            String needed = supplier ? "A receipt needs a supplier." : "A sale needs a customer.";
            return party.getDisplayName() + " is saved as " + role + ". " + needed;
        }
        if (named.size() > 1) {
            return "I heard " + partyName + ", and more than one saved party has that name.";
        }
        Party close = closeParty(userId, partyName);
        if (close != null) {
            return "I heard " + partyName + ". The saved party is " + close.getDisplayName() + ". Say that name if it is the same party.";
        }
        return "I heard " + partyName + ", and no saved party has that name.";
    }

    private Party closeParty(UUID userId, String partyName) {
        String wanted = Names.normalize(partyName);
        List<Party> close = parties.list(userId).stream()
                .filter(party -> distance(wanted, party.getNormalizedName()) == 1)
                .toList();
        return close.size() == 1 ? close.get(0) : null;
    }

    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int cost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(Math.min(current[column - 1] + 1, previous[column] + 1), previous[column - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private String context(UUID userId) {
        StringBuilder text = new StringBuilder();
        dialogue.get(userId).ifPresent(state -> text.append("focused conversation: draft_id=").append(state.draftId())
                .append(" question=").append(state.question()).append(" proposed_name=").append(state.name())
                .append(" proposed_unit=").append(state.unit()).append("\n"));
        text.append("now=").append(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        questions.get(userId).ifPresent(question -> text.append("\npending balance question: party=")
                .append(question.partyName()).append(" side=").append(question.side())
                .append(" stated_amount_paise=").append(question.amountPaise())
                .append(". A party-name answer continues this question; do not greet or ask for goods."));
        String history = memory.recent(userId);
        if (!history.isBlank()) {
            text.append("\nrecent:\n").append(history);
        }
        List<DraftBook.OpenDraft> open = drafts.open(userId);
        if (!open.isEmpty()) {
            text.append("\nopen drafts:");
            text.append(drafts.openContext(userId));
        }
        List<Party> known = parties.list(userId);
        if (!known.isEmpty()) {
            text.append("\nparties:");
            int count = 0;
            for (Party party : known) {
                if (count == 100) {
                    break;
                }
                text.append(' ').append(party.getDisplayName()).append(" id=").append(party.getId()).append(" [").append(party.getRole()).append("]");
                count++;
            }
        }
        text.append("\nproducts (first 100; absence does not prove a product is new):").append(catalog.interpretationContext(userId));
        text.append("\nremembered location labels:").append(locations.rememberedLabels(userId));
        text.append("\ngodowns:");
        for (Godown godown : godowns.findByBusinessIdOrderByCodeAsc(tenants.currentBusinessId(userId))) {
            text.append("\ncode=").append(godown.getCode()).append(" name=").append(godown.getName());
        }
        return text.toString();
    }

    private UUID bindDraft(
            UUID userId,
            UUID draftId,
            String turn,
            String action,
            UUID customerId,
            UUID productId,
            UUID locationId,
            BigDecimal quantity,
            String unit,
            Long amount,
            String method
    ) {
        List<DraftBook.OpenDraft> open = drafts.open(userId);
        if ("new_action".equals(turn) && action != null) {
            if (money(kindOf(action))) {
                suspendDifferent(userId, open, kindOf(action));
            }
            return null;
        }
        if (draftId != null) {
            var selected = open.stream().filter(row -> row.id().equals(draftId)).findFirst().orElse(null);
            if (selected != null && (action == null || kindOf(action).equals(selected.kind())
                    || selected.kind() == null && ("receive".equals(action) || "sale".equals(action)))) return selected.id();
        }
        boolean locationOnly = action == null && locationId != null && quantity == null && unit == null
                && amount == null && customerId == null && productId == null && method == null;
        if (locationOnly) {
            List<DraftBook.OpenDraft> needing = open.stream()
                    .filter(draft -> draft.missing() != null && draft.missing().contains("location"))
                    .toList();
            if (needing.size() == 1) {
                return needing.get(0).id();
            }
            if (needing.size() > 1) {
                return null;
            }
        }
        if (draftId != null) {
            DraftBook.OpenDraft selected = open.stream().filter(draft -> draft.id().equals(draftId)).findFirst().orElse(null);
            if (selected != null && action != null && selected.kind() != null && !kindOf(action).equals(selected.kind())) {
                if (money(kindOf(action))) {
                    drafts.suspend(userId, selected.id());
                }
                return null;
            }
            if (selected != null && (action == null || kindOf(action).equals(selected.kind())
                    || selected.kind() == null && ("receive".equals(action) || "sale".equals(action)))) {
                return selected.id();
            }
        }
        if (action != null) {
            String kind = kindOf(action);
            List<DraftBook.OpenDraft> same = open.stream().filter(draft -> kind.equals(draft.kind())).toList();
            if (same.size() == 1 && (turn == null || "answer_to_clarification".equals(turn) || "edit_action".equals(turn))) {
                return same.get(0).id();
            }
            if (money(kind)) {
                suspendDifferent(userId, open, kind);
            }
            return null;
        }
        if (open.size() == 1 && (turn == null || "answer_to_clarification".equals(turn) || "edit_action".equals(turn))) {
            return open.get(0).id();
        }
        return null;
    }

    private void suspendDifferent(UUID userId, List<DraftBook.OpenDraft> open, String kind) {
        for (DraftBook.OpenDraft draft : open) {
            if (!kind.equals(draft.kind())) {
                drafts.suspend(userId, draft.id());
            }
        }
    }

    private static boolean money(String kind) {
        return "PAYMENT".equals(kind) || "RECEIVABLE".equals(kind) || "PAYABLE".equals(kind) || "OUTGOING_PAYMENT".equals(kind);
    }

    private static String kindOf(String action) {
        return switch (action) {
            case "sale" -> "SALE";
            case "receive" -> "RECEIPT";
            case "payment" -> "PAYMENT";
            case "receivable" -> "RECEIVABLE";
            case "payable" -> "PAYABLE";
            case "outgoing_payment" -> "OUTGOING_PAYMENT";
            default -> null;
        };
    }

    private static String firstText(JsonNode reply, String... fields) {
        for (String field : fields) {
            String value = text(reply, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Boolean flag(JsonNode reply, String field) {
        JsonNode node = reply.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String value = node.asText("").trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "yes".equals(value)) {
            return true;
        }
        if ("false".equals(value) || "no".equals(value)) {
            return false;
        }
        return null;
    }

    private static String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left + " " + right;
    }

    private UUID party(UUID userId, String name, boolean supplier) {
        if (name == null) {
            return null;
        }
        List<Party> matches = parties.findByName(userId, name).stream()
                .filter(party -> supplier
                        ? party.getRole() == PartyRole.SUPPLIER || party.getRole() == PartyRole.BOTH
                        : party.getRole() == PartyRole.CUSTOMER || party.getRole() == PartyRole.BOTH)
                .toList();
        return matches.size() == 1 ? matches.get(0).getId() : null;
    }

    private static LocalDate localDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static LocalTime localTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
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
        String wanted = code.toLowerCase(Locale.ROOT);
        List<Godown> matches = godowns.findByBusinessIdOrderByCodeAsc(businessId).stream()
                .filter(godown -> godown.getCode().equalsIgnoreCase(wanted) || godown.getName().equalsIgnoreCase(code)
                        || (godownNumber(code) != null && (godownNumber(code).equals(godownNumber(godown.getCode()))
                        || godownNumber(code).equals(godownNumber(godown.getName())))))
                .toList();
        return matches.size() == 1 ? matches.get(0).getId() : null;
    }

    private static String godownNumber(String value) {
        if (value == null) return null;
        var match = java.util.regex.Pattern.compile("(?iu)^(?:godown|godam|गोदाम|गोडाउन|गोडाम|g)\\s*(?:no\\.?|number|नंबर|नम्बर)?\\s*(.+)$").matcher(value.trim());
        if (!match.matches()) return null;
        String number = cleanWords(match.group(1));
        String wordNumber = switch (number) {
            case "one", "वन", "एक" -> "1";
            case "two", "टू", "दो" -> "2";
            case "three", "थ्री", "तीन" -> "3";
            case "four", "चार", "फोर" -> "4";
            case "five", "पांच", "पाँच", "फाइव" -> "5";
            case "six", "छह", "सिक्स" -> "6";
            case "seven", "सात", "सेवन" -> "7";
            case "eight", "आठ", "एट" -> "8";
            case "nine", "नौ", "नाइन" -> "9";
            case "ten", "दस", "टेन" -> "10";
            default -> null;
        };
        if (wordNumber != null) return wordNumber;
        if (!number.matches("[0-9०-९]+")) return null;
        StringBuilder digits = new StringBuilder();
        number.codePoints().forEach(c -> digits.append(Character.digit(c, 10)));
        return new java.math.BigInteger(digits.toString()).toString();
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
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isTextual() && node.asText().matches("\\d+")) {
            return Long.parseLong(node.asText());
        }
        return null;
    }

    private static String text(JsonNode reply, String field) {
        JsonNode node = reply.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() || "null".equals(value) ? null : value.trim();
    }
}
