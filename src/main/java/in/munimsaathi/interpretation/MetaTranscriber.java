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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class MetaTranscriber implements Transcriber {

    private static final Logger log = LoggerFactory.getLogger(MetaTranscriber.class);

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
        byte[] wav = WavAudio.toWav(audio, mediaType);
        String endpoint = trimSlash(baseUrl) + "/asr/transcribe";
        String model = properties.meta().transcriptionModel();
        long started = System.nanoTime();
        String boundary = "munim-voice";
        String body = client.post()
                .uri(endpoint)
                .header("Authorization", "Bearer " + apiKey.trim())
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary))
                .body(multipart(boundary, model, wav))
                .retrieve()
                .body(String.class);
        log.info(
                "Meta voice request endpoint={} status={} durationMs={}",
                endpoint,
                "ok",
                (System.nanoTime() - started) / 1_000_000
        );
        try {
            JsonNode transcript = mapper.readTree(body).path("transcript");
            if (transcript.isMissingNode() || transcript.asText().isBlank()) {
                throw new IllegalArgumentException("The transcript was empty.");
            }
            return transcript.asText();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("The transcript was not readable.", exception);
        }
    }

    private byte[] multipart(String boundary, String model, byte[] wav) {
        byte[] request;
        try {
            request = mapper.writeValueAsBytes(Map.of(
                    "model", model,
                    "audioEncoding", "WAV",
                    "mode", "PUSH_TO_TALK"
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The voice request could not be written.", exception);
        }
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            part(body, boundary, "request", "request.json", "application/json", request);
            part(body, boundary, "audio", "note.wav", "audio/wav", wav);
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return body.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("The voice request could not be written.", exception);
        }
    }

    private static void part(ByteArrayOutputStream body, String boundary, String name, String fileName, String type, byte[] content) throws IOException {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n";
        body.write(header.getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String trimSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
