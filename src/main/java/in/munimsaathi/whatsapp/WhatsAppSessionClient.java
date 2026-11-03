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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WhatsAppSessionClient.class);
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

    @Override
    public byte[] download(String mediaId) {
        MunimProperties.Whatsapp whatsapp = properties.whatsapp();
        if (whatsapp == null || blank(whatsapp.accessToken()) || blank(whatsapp.graphApiVersion()) || blank(mediaId)) {
            log.warn("WhatsApp media unavailable: missing configuration or media id");
            return null;
        }
        String token = whatsapp.accessToken().trim();
        for (int attempt = 0; attempt < 2; attempt++) {
            String stage = "lookup";
            try {
                String lookup = client.get()
                        .uri("https://graph.facebook.com/" + whatsapp.graphApiVersion().trim() + "/" + mediaId)
                        .header("Authorization", "Bearer " + token)
                        .retrieve().body(String.class);
                String url = mapper.readTree(lookup).path("url").asText(null);
                if (blank(url)) {
                    log.warn("WhatsApp media failed stage=lookup mediaId={} reason=missing_url", mediaId);
                    return null;
                }
                stage = "content";
                // This URL is already signed and encoded; a URI template would encode percent signs again.
                byte[] bytes = client.get().uri(java.net.URI.create(url))
                        .header("Authorization", "Bearer " + token).retrieve().body(byte[].class);
                if (bytes != null && bytes.length > 0) {
                    log.info("WhatsApp media downloaded mediaId={} bytes={}", mediaId, bytes.length);
                    return bytes;
                }
                log.warn("WhatsApp media failed stage=content mediaId={} reason=empty_body attempt={}", mediaId, attempt + 1);
            } catch (org.springframework.web.client.RestClientResponseException failure) {
                int status = failure.getStatusCode().value();
                String code = "unknown", trace = "unknown";
                try {
                    JsonNode error = mapper.readTree(failure.getResponseBodyAsString()).path("error");
                    code = error.path("code").asText("unknown");
                    trace = error.path("fbtrace_id").asText("unknown");
                } catch (Exception ignored) { }
                log.warn("WhatsApp media failed stage={} mediaId={} status={} providerCode={} trace={} attempt={}",
                        stage, mediaId, status, code, trace, attempt + 1);
                if (status != 429 && status < 500) return null;
            } catch (org.springframework.web.client.ResourceAccessException failure) {
                log.warn("WhatsApp media failed stage={} mediaId={} errorType={} attempt={}",
                        stage, mediaId, failure.getCause() == null ? failure.getClass().getSimpleName() : failure.getCause().getClass().getSimpleName(), attempt + 1);
            } catch (Exception failure) {
                // Never log authorization headers, signed URLs, or response bodies.
                log.warn("WhatsApp media failed stage={} mediaId={} errorType={}", stage, mediaId, failure.getClass().getSimpleName());
                return null;
            }
        }
        return null;
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
