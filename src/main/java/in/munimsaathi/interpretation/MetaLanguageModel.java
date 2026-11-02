package in.munimsaathi.interpretation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MetaLanguageModel implements LanguageModel {

    private static final Logger log = LoggerFactory.getLogger(MetaLanguageModel.class);

    static final String INSTRUCTIONS = """
            Return one JSON object with keys turn, target_draft_id, action, intent, party_name, product_name, quantity, unit, amount_paise, payment_method, payment_purpose, cause, balance_side, location_code, location_label, reminder_date, reminder_time, lines, create_party, create_product, requested_tools, and reply.
            turn is new_action, answer_to_clarification, edit_action, read_query, approve, cancel, or unclear.
            For cancel, target_draft_id is an exact ID from the supplied open drafts only when the owner unambiguously identifies it; otherwise null.
            action is sale, goods_received, customer_payment_received, standalone_receivable, supplier_payable, outgoing_payment, party_balance, remember_location, locate_goods, reminder, or null.
            intent may repeat action for a sale or a receipt. quantity is a decimal string or null. Leave a field null when the message does not state it.
            Write every human-facing reply in natural Roman Hindi/Hinglish. Keep names and numbers unchanged. Ask one relevant question at a time; do not reset a pending action just because the next message is a short amount, party name, or Cash/UPI.
            "Maine Anisha ko paanch hazaar diye" or "maine pay kiye hain" means outgoing_payment: money was already given. It is NOT supplier_payable (money still owed) and NOT customer_payment_received.
            outgoing_payment has payment_purpose bill_payment (settling existing dues), advance, loan, or null when unknown. Ask which purpose; never infer a new payable from money already paid. Preserve party and amount while asking.
            If the user corrects an unpaid/debt proposal with "maine pay kiye hain", return edit_action with outgoing_payment; keep the previous confirmed amount unless explicitly changed.
            A party-name, amount-only, purpose-only or Cash/UPI answer to an open question is answer_to_clarification for that action. Do not treat it as a new stock form.
            balance_side is payable (owner owes party), receivable (party owes owner), or both for a balance query. Preserve this direction when a party is clarified.
            "Anisha ko paanch hazaar dene hain" states money the owner owes; it does not prove a new debt or an outgoing payment already made. Ask whether it is new or existing before proposing an addition.
            "Agarwal se paise lene hain" states money owed to the owner; do not assume a new loan. "Rahul se paise mil gaye" means money received.
            A storage fact such as "Anil bhai ka maal Godown 1 mein rakha hai" means remember_location, NOT goods_received. location_label is the complete goods label (e.g. "Anil bhai ka maal"). It needs a label and godown, not a registered party/product, quantity, price or ownership assumption. It never adds stock or money. "Kahan rakha hai?" is locate_goods, using the exact remembered location_label when present.
            Only explicit arrival/purchase language ("aaya", "kharida", "liya") can propose a receipt. If storage vs receipt/transfer or goods ownership is unclear, ask one question and return no business action. A person's goods may be customer-owned; do not turn a place note into business-owned sellable stock.
            The focused conversation and pending question are authoritative context. A short answer continues that draft. After being asked whether to create a new party/product, "haan", "nayi party hai", "haan bana do" or a clear equivalent is consent to create that exact offered entity; it is not approval to post money/stock. Negative or conflicting "haan nahi" is not consent. Do not require the English word create. Repeating a product name identifies it but is not consent to register it.
            A new transaction or read query can change topic; do not pull fields from a different transaction. Never claim something was saved or posted unless the supplied system context says it was posted. At most propose a draft or ask a question.
            Buying or receiving physical goods ("Agarwal Petha se 1000 bori plastic liya, Godown 1 mein rakha") means goods_received, not outgoing_payment. Extract the full supplier name, product, quantity, stated unit and godown even if they are not yet registered. Unknown price does not block a stock-only receipt.
            Use the supplied catalog and godown list. For Hindi speech, use an existing canonical party/product name only when clearly the same entity; do not shorten "Agarwal Petha" to "Agarwal". Do not silently choose between variants or similarly named parties. Return the exact stored godown code and approved unit label when available. Never invent a pack conversion or change bori to kg, or boxes to pieces, merely to match an available catalog unit.
            When clarifying an open draft, include its exact target_draft_id. If multiple drafts could match, ask which one. Keep quantity, unit, party, product and location already stated. Missing resolution is not the same as the owner never stating a value.
            Set create_product true only when the owner explicitly requests registering a new product and states its base unit. Mentioning an unknown product is not consent to create it. A product's base unit is not a box-to-piece conversion.
            Set create_party true only when the owner explicitly asks to create/add the named party; never infer consent from mentioning a name.
            When answering a pending party question, preserve its action, direction and amount. A message such as "Party Anisha Gajak" answers that question; it is not a greeting.
            amount_paise is integer paise: 5000 rupees is 500000 paise. Do not treat rupees as paise.
            A payment or a debt names the party and the amount. It does not name a product, a godown, or a quantity unless the owner is also moving goods.
            Use confirm_existing_debt as action when the owner is describing money that is already owed. Use cause new only when the owner says the debt is new.
            When the owner is greeting or is not stating a receipt, sale, payment, place, or reminder, leave the business fields null and put a short reply in reply.
            lines is an array of product_name, quantity, unit, and amount_paise when the owner names more than one item. A single item uses the top-level fields.
            reminder_date is yyyy-mm-dd and reminder_time is HH:mm only when the owner stated them.
            Do not convert boxes into pieces. Do not invent quantity, price, godown, tax, or a sending time.
            requested_tools must be an empty array unless the owner asked for an e-way bill.
            When the owner asks for an e-way bill, requested_tools may contain only prepare_eway_bill, plus the invoice and the transport and tax fields the owner stated.
            Leave tax, GSTIN, HSN, distance, and transport null when the owner did not state them.
            You cannot approve, post stock, post money, send a message, or submit a document.
            """;

    private final MunimProperties properties;
    private final RestClient client;
    private final ObjectMapper mapper;

    public MetaLanguageModel(MunimProperties properties, RestClient.Builder builder, ObjectMapper mapper) {
        this.properties = properties;
        this.client = builder.build();
        this.mapper = mapper;
    }

    @Override
    public String complete(String userText) {
        return post(userText);
    }

    @Override
    public String completeDocument(String instruction, String mediaType, String fileName, byte[] content) {
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if (type.startsWith("image/")) {
            String dataUrl = "data:" + type + ";base64," + Base64.getEncoder().encodeToString(content);
            return post(List.of(
                    Map.of("type", "text", "text", instruction),
                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
            ));
        }
        String documentText = "application/pdf".equals(type) ? PdfPages.text(content) : "";
        return post(instruction + "\n" + documentText);
    }

    private String post(Object userContent) {
        String apiKey = properties.meta().apiKey();
        String baseUrl = properties.meta().baseUrl();

        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Meta API key is not configured.");
        }
        apiKey = apiKey.trim();
        String model = configuredModel(properties.meta().reasoningModel());
        String endpoint = trimSlash(baseUrl) + "/chat/completions";
        byte[] payload = payload(model, userContent);
        log.info("Meta chat request baseUrl={} endpoint={} model={} bodyBytes={} keyLength={}", baseUrl, endpoint, model, payload.length, apiKey.length());
        String body = client.post()
                .uri(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(payload.length)
                .body(payload)
                .retrieve()
                .body(String.class);
        try {
            JsonNode content = mapper.readTree(body).path("choices").path(0).path("message").path("content");
            String text = textOf(content);
            if (text.isBlank()) {
                throw new IllegalArgumentException("The model reply was empty.");
            }
            return text;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("The model reply was not readable.", exception);
        }
    }

    private static String textOf(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return content.asText("");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            String piece = part.isTextual() ? part.asText() : part.path("text").asText("");
            if (piece.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(piece);
        }
        return text.toString();
    }

    private byte[] payload(String model, Object userContent) {
        try {
            String instructions = INSTRUCTIONS;
            String context = TurnContext.current();
            if (context != null && !context.isBlank()) {
                instructions = instructions + "\nCurrent business context, for this business only:\n" + context;
            }
            return mapper.writeValueAsBytes(Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", instructions),
                            Map.of("role", "user", "content", userContent)
                    )
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The model request could not be written.", exception);
        }
    }

    private static String configuredModel(String model) {
        if (model == null || model.isBlank()) {
            return "muse-spark-1.3";
        }
        return model.trim();
    }

    private static String trimSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
