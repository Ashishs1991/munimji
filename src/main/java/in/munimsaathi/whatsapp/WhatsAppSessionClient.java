package in.munimsaathi.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import in.munimsaathi.reminder.ProviderReceipt;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class WhatsAppSessionClient implements SessionReply {

    private final MunimProperties properties;
    private final RestClient client;
    private final ObjectMapper mapper;

    public WhatsAppSessionClient(MunimProperties properties, RestClient.Builder builder, ObjectMapper mapper) {
        this.properties = properties;
        this.client = builder.build();
        this.mapper = mapper;
    }

    @Override
    public ProviderReceipt sendText(String phone, String text) {
        MunimProperties.Whatsapp whatsapp = properties.whatsapp();
        if (whatsapp == null
                || blank(whatsapp.accessToken())
                || blank(whatsapp.phoneNumberId())
                || blank(whatsapp.graphApiVersion())
                || blank(phone)
                || blank(text)) {
            return new ProviderReceipt("blocked", null);
        }
        String body = client.post()
                .uri("https://graph.facebook.com/" + whatsapp.graphApiVersion() + "/" + whatsapp.phoneNumberId() + "/messages")
                .header("Authorization", "Bearer " + whatsapp.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "messaging_product", "whatsapp",
                        "to", phone,
                        "type", "text",
                        "text", Map.of("preview_url", false, "body", text)
                ))
                .retrieve()
                .body(String.class);
        return read(body);
    }

    private ProviderReceipt read(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root.has("error")) {
                return new ProviderReceipt("failed", null);
            }
            JsonNode id = root.path("messages").path(0).path("id");
            if (id.isMissingNode() || id.asText().isBlank()) {
                return new ProviderReceipt("outcome_unknown", null);
            }
            return new ProviderReceipt("accepted", id.asText());
        } catch (Exception exception) {
            return new ProviderReceipt("outcome_unknown", null);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
