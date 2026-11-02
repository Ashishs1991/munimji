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
            PendingBalanceQuestions questions
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
        TurnContext.set(context(userId));
        String modelText;
        try {
            modelText = model.complete(text);
        } finally {
            TurnContext.clear();
        }
        JsonNode reply = commandJson(modelText);
        if (reply == null) {
            return new InterpretationOutcome(null, List.of(), null, modelText.trim());
        }
        return apply(userId, draftId, reply, occurredOn, occurredAt);
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
        return apply(userId, draftId, reply, occurredOn, occurredAt);
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
            boolean pendingQuestion = questions.get(userId).isPresent();
            questions.clear(userId);
            if (pendingQuestion && draftId == null && text(reply, "target_draft_id") == null) {
                return new InterpretationOutcome(null, refused, null, "Cancelled the balance question. Nothing was posted.");
            }
            return new InterpretationOutcome(null, refused, null, cancelDraft(userId, draftId, text(reply, "target_draft_id")));
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
            default -> null;
        };
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
        boolean supplier = "receive".equals(action) || "payable".equals(action);
        if (action == null && "answer_to_clarification".equals(turn)) {
            var open = drafts.open(userId);
            if (open.size() == 1) {
                supplier = "RECEIPT".equals(open.get(0).kind()) || "PAYABLE".equals(open.get(0).kind());
            }
        }
        UUID customerId = party(userId, text(reply, "party_name"), supplier);
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
        boolean money = "payment".equals(action) || "receivable".equals(action) || "payable".equals(action);
        JsonNode lineNodes = reply.get("lines");
        if (!money && lineNodes != null && lineNodes.isArray() && !lineNodes.isEmpty()) {
            List<DraftLineSpec> specs = new ArrayList<>();
            for (JsonNode node : lineNodes) {
                specs.add(lineSpec(userId, node));
            }
            UUID lineTarget = draftId;
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
        boolean stated = action != null || customerId != null || productId != null || locationId != null
                || quantity != null || unit != null || amount != null || method != null;
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
            PartyRole role = supplier ? PartyRole.SUPPLIER : PartyRole.CUSTOMER;
            customerId = parties.create(userId, text(reply, "party_name"), role).getId();
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
        UUID bound = bindDraft(userId, draftId, turn, action, customerId, productId, locationId, quantity, unit, amount, method);
        String boundKind = bound == null ? null : drafts.kindOf(userId, bound);
        boolean moneyDraft = money || "PAYMENT".equals(boundKind) || "RECEIVABLE".equals(boundKind) || "PAYABLE".equals(boundKind);
        if (moneyDraft) {
            String moneyAction = action != null ? action : switch (boundKind) {
                case "RECEIVABLE" -> "receivable";
                case "PAYABLE" -> "payable";
                default -> "payment";
            };
            UUID allocates = null;
            if ("payment".equals(moneyAction) && customerId != null && amount != null && flag(reply, "allocate") != Boolean.FALSE) {
                allocates = posting.soleOpenSale(userId, customerId, amount).orElse(null);
            }
            String direction = "payable".equals(moneyAction) ? "payable" : "receivable";
            DraftProposal draft = bound == null
                    ? drafts.proposeMoney(userId, moneyAction, customerId, amount, method, allocates, text(reply, "cause"), direction, occurredOn, occurredAt)
                    : drafts.continueMoney(userId, bound, moneyAction, customerId, amount, method, allocates, text(reply, "cause"), direction, occurredOn, occurredAt);
            String note = clarification(userId, text(reply, "party_name"), customerId, supplier, null, productId);
            if (customerId == null && text(reply, "party_name") == null) {
                note = join(note, "Who was this?");
            }
            if (customerId == null && text(reply, "party_name") != null) {
                note = join(note, "Say create if I should add this party.");
            }
            if (draft.missingFields().contains("payment_method")) {
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
            for (DraftBook.OpenDraft draft : open) {
                text.append('\n').append(draft.id()).append(' ').append(draft.kind()).append(" missing=").append(draft.missing());
            }
        }
        List<Party> known = parties.list(userId);
        if (!known.isEmpty()) {
            text.append("\nparties:");
            int count = 0;
            for (Party party : known) {
                if (count == 20) {
                    break;
                }
                text.append(' ').append(party.getDisplayName()).append(" [").append(party.getRole()).append("]");
                count++;
            }
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
            if (selected != null && action != null && !kindOf(action).equals(selected.kind())) {
                if (money(kindOf(action))) {
                    drafts.suspend(userId, selected.id());
                }
                return null;
            }
            if (selected != null && (action == null || kindOf(action).equals(selected.kind()))) {
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
        return "PAYMENT".equals(kind) || "RECEIVABLE".equals(kind) || "PAYABLE".equals(kind);
    }

    private static String kindOf(String action) {
        return switch (action) {
            case "sale" -> "SALE";
            case "receive" -> "RECEIPT";
            case "payment" -> "PAYMENT";
            case "receivable" -> "RECEIVABLE";
            case "payable" -> "PAYABLE";
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
                .filter(godown -> godown.getCode().equalsIgnoreCase(wanted) || godown.getName().equalsIgnoreCase(code))
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
