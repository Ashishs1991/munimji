package in.munimsaathi.eway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class EwayClient implements EwayGateway {

    private final MunimProperties properties;
    private final RestClient client;
    private final ObjectMapper mapper;

    public EwayClient(MunimProperties properties, RestClient.Builder builder, ObjectMapper mapper) {
        this.properties = properties;
        this.client = builder.build();
        this.mapper = mapper;
    }

    @Override
    public EwayReply generate(String requestJson) {
        try {
            String token = authToken();
            if (token == null) {
                return new EwayReply("0", null, null, null, "The e-way portal did not grant a token.", false);
            }
            return post("GENEWAYBILL", requestJson, token, true);
        } catch (RestClientException exception) {
            return EwayReply.unknown("The e-way portal did not answer.");
        }
    }

    @Override
    public EwayReply lookup(String docType, String docNo) {
        try {
            String token = authToken();
            if (token == null) {
                return EwayReply.unknown("The e-way portal did not grant a token.");
            }
            String requestJson = mapper.writeValueAsString(Map.of("docType", docType, "docNo", docNo));
            return post("GETEWAYBILL", requestJson, token, false);
        } catch (RestClientException exception) {
            return EwayReply.unknown("The e-way portal did not answer.");
        } catch (Exception exception) {
            return EwayReply.unknown("The e-way lookup could not be sent.");
        }
    }

    private EwayReply post(String action, String requestJson, String token, boolean billRequestSent) {
        MunimProperties.Eway eway = configured();
        String data = Base64.getEncoder().encodeToString(requestJson.getBytes(StandardCharsets.UTF_8));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("data", data);
        try {
            String response = client.post()
                    .uri(endpoint(eway.baseUrl(), "/ewaybillapi/v1.03/ewayapi"))
                    .header("client-id", eway.clientId())
                    .header("client-secret", eway.clientSecret())
                    .header("gstin", eway.gstin())
                    .header("authtoken", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(response, billRequestSent);
        } catch (RestClientException exception) {
            return billRequestSent
                    ? EwayReply.unknownSent("The provider did not answer.")
                    : EwayReply.unknown("The provider did not answer.");
        }
    }

    private String authToken() {
        MunimProperties.Eway eway = configured();
        String appKey = UUID.randomUUID().toString().replace("-", "");
        try {
            String credentials = mapper.writeValueAsString(Map.of(
                    "username", eway.username(),
                    "password", eway.password(),
                    "app_key", appKey
            ));
            String data = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            Map<String, String> body = new LinkedHashMap<>();
            body.put("action", "ACCESSTOKEN");
            body.put("data", data);
            String response = client.post()
                    .uri(endpoint(eway.baseUrl(), "/ewaybillapi/v1.03/auth"))
                    .header("client-id", eway.clientId())
                    .header("client-secret", eway.clientSecret())
                    .header("gstin", eway.gstin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(response);
            if (!"1".equals(text(root.get("status")))) {
                return null;
            }
            String token = text(root.get("authtoken"));
            return token == null || token.isBlank() ? null : token;
        } catch (RestClientException exception) {
            throw exception;
        } catch (Exception exception) {
            return null;
        }
    }

    private EwayReply parse(String body, boolean billRequestSent) {
        try {
            JsonNode root = mapper.readTree(body);
            String status = text(root.get("status"));
            JsonNode payload = root;
            JsonNode data = root.get("data");
            if (data != null && data.isTextual()) {
                byte[] decoded = Base64.getDecoder().decode(data.asText());
                payload = mapper.readTree(decoded);
            }
            return new EwayReply(
                    status,
                    text(payload.get("ewayBillNo")),
                    text(payload.get("ewayBillDate")),
                    text(payload.get("validUpto")),
                    errorText(root.get("error")),
                    billRequestSent
            );
        } catch (Exception exception) {
            return new EwayReply(null, null, null, null, "The provider response was not readable.", billRequestSent);
        }
    }

    private MunimProperties.Eway configured() {
        MunimProperties.Eway eway = properties.eway();
        if (eway == null
                || !eway.enabled()
                || blank(eway.baseUrl())
                || blank(eway.clientId())
                || blank(eway.clientSecret())
                || blank(eway.gstin())
                || blank(eway.username())
                || blank(eway.password())) {
            throw new IllegalStateException("E-way submission is not configured.");
        }
        return eway;
    }

    private static String endpoint(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }

    private static String errorText(JsonNode error) {
        if (error == null || error.isNull()) {
            return null;
        }
        if (error.isTextual()) {
            return error.asText();
        }
        JsonNode codes = error.get("errorCodes");
        if (codes != null && !codes.isNull()) {
            return codes.asText();
        }
        return error.toString();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
