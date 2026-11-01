package in.munimsaathi.interpretation;

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
            Return one JSON object with keys intent, party_name, product_name, quantity, unit, amount_paise, location_code, reminder_date, reminder_time, lines, and requested_tools.
            intent is sale, receive, purchase, balance, locate, reminder, or null. quantity is a decimal string or null. Leave a field null when the message does not state it.
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
        String model = configuredModel(properties.meta().reasoningModel());
        String endpoint = trimSlash(baseUrl) + "/chat/completions";
        log.info("Meta chat request baseUrl={} endpoint={} model={}", baseUrl, endpoint, model);
        String body = client.post()
                .uri(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", model,
                        "messages", List.of(
                                Map.of("role", "system", "content", INSTRUCTIONS),
                                Map.of("role", "user", "content", userContent)
                        )
                ))
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
