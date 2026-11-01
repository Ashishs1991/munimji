package in.munimsaathi.interpretation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.Party;
import in.munimsaathi.catalog.PartyBook;
import in.munimsaathi.catalog.PartyRole;
import in.munimsaathi.catalog.Product;
import in.munimsaathi.draft.DraftBook;
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
            ReminderBook reminders
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
        JsonNode reply;
        try {
            reply = mapper.readTree(model.complete(text));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("The model reply was not readable.", exception);
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
        JsonNode reply;
        try {
            reply = mapper.readTree(modelJson);
        } catch (Exception exception) {
            throw new IllegalArgumentException("The model reply was not readable.", exception);
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
        UUID ewayDraftId = ewayDraft(userId, reply);
        String intent = text(reply, "intent");
        String normalized = intent == null ? "" : intent.toLowerCase(Locale.ROOT);
        String action = switch (normalized) {
            case "sale" -> "sale";
            case "receive", "purchase" -> "receive";
            default -> null;
        };
        boolean supplier = "receive".equals(action);
        UUID customerId = party(userId, text(reply, "party_name"), supplier);
        UUID productId = product(userId, text(reply, "product_name"));
        UUID locationId = location(userId, text(reply, "location_code"));
        BigDecimal quantity = quantity(reply.get("quantity"));
        String unit = text(reply, "unit");
        Long amount = amount(reply.get("amount_paise"));
        String answer = answerFor(userId, normalized, customerId, productId, reply);
        if (answer != null) {
            return new InterpretationOutcome(null, refused, ewayDraftId, answer);
        }
        if (draftId == null && action == null && customerId == null && productId == null && locationId == null
                && quantity == null && unit == null && amount == null) {
            return new InterpretationOutcome(null, refused, ewayDraftId, null);
        }
        DraftProposal draft = draftId == null
                ? drafts.propose(userId, action, customerId, productId, locationId, quantity, unit, amount, occurredOn, occurredAt)
                : drafts.continueDraft(userId, draftId, action, customerId, productId, locationId, quantity, unit, amount, occurredOn, occurredAt);
        return new InterpretationOutcome(draft, refused, ewayDraftId, null);
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
