package in.munimsaathi.interpretation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

@Component
public class MetaTranscriber implements Transcriber {

    private final MunimProperties properties;
    private final RestClient client;
    private final ObjectMapper mapper;

    public MetaTranscriber(MunimProperties properties, RestClient.Builder builder, ObjectMapper mapper) {
        this.properties = properties;
        this.client = builder.build();
        this.mapper = mapper;
    }

    @Override
    public String transcribe(byte[] audio, String mediaType) {
        String apiKey = properties.meta().apiKey();
        String baseUrl = properties.meta().baseUrl();
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Meta API key is not configured.");
        }
        String body = client.post()
                .uri(trimSlash(baseUrl) + "/audio/transcriptions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", properties.meta().transcriptionModel(),
                        "media_type", mediaType,
                        "audio_base64", Base64.getEncoder().encodeToString(audio)
                ))
                .retrieve()
                .body(String.class);
        try {
            JsonNode text = mapper.readTree(body).path("text");
            if (text.isMissingNode() || text.asText().isBlank()) {
                throw new IllegalArgumentException("The transcript was empty.");
            }
            return text.asText();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("The transcript was not readable.", exception);
        }
    }

    private static String trimSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
