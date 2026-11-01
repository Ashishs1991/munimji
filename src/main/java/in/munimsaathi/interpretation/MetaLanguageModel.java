package in.munimsaathi.interpretation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class MetaLanguageModel implements LanguageModel {

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
        String apiKey = properties.meta().apiKey();
        String baseUrl = properties.meta().baseUrl();
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Meta API key is not configured.");
        }
        String body = client.post()
                .uri(trimSlash(baseUrl) + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", properties.meta().reasoningModel(),
                        "messages", List.of(
                                Map.of("role", "system", "content", INSTRUCTIONS),
                                Map.of("role", "user", "content", userText)
                        )
                ))
                .retrieve()
                .body(String.class);
        try {
            JsonNode content = mapper.readTree(body).path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new IllegalArgumentException("The model reply was empty.");
            }
            return content.asText();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("The model reply was not readable.", exception);
        }
    }

    private static String trimSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
