package in.munimsaathi.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class WhatsAppMessageSender implements MessageSender {

    private final MunimProperties properties;
    private final RestClient client;
    private final ObjectMapper mapper;

    public WhatsAppMessageSender(MunimProperties properties, RestClient.Builder builder, ObjectMapper mapper) {
        this.properties = properties;
        this.client = builder.build();
        this.mapper = mapper;
    }

    @Override
    public ProviderReceipt send(String contact, String templateName, String payload) {
        MunimProperties.Whatsapp whatsapp = properties.whatsapp();
        if (whatsapp == null
                || blank(whatsapp.accessToken())
                || blank(whatsapp.phoneNumberId())
                || blank(whatsapp.graphApiVersion())) {
            return new ProviderReceipt("blocked", null);
        }
        String body = client.post()
                .uri("https://graph.facebook.com/" + whatsapp.graphApiVersion() + "/" + whatsapp.phoneNumberId() + "/messages")
                .header("Authorization", "Bearer " + whatsapp.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "messaging_product", "whatsapp",
                        "to", contact,
                        "type", "template",
                        "template", Map.of(
                                "name", templateName,
                                "language", Map.of("code", "en"),
                                "components", new Object[] {}
                        )
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
