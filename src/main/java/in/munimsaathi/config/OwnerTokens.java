package in.munimsaathi.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class OwnerTokens {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OwnerTokens() {
    }

    public static String sign(String secret, UUID userId, Instant expiresAt) {
        String body = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}")
                + "."
                + encode("{\"sub\":\"" + userId + "\",\"exp\":" + expiresAt.getEpochSecond() + "}");
        return body + "." + encode(hmac(secret, body));
    }

    public static UUID verify(String secret, String token) {
        if (secret == null || secret.isBlank() || token == null || token.isBlank()) {
            throw new IllegalArgumentException("Sign in is required.");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Sign in is required.");
        }
        byte[] expected = hmac(secret, parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Sign in is required.");
        }
        if (expected.length != actual.length || !MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("Sign in is required.");
        }
        try {
            JsonNode payload = MAPPER.readTree(decode(parts[1]));
            long expiresAt = payload.path("exp").asLong(0);
            if (expiresAt <= Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("Sign in is required.");
            }
            return UUID.fromString(payload.path("sub").asText());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Sign in is required.");
        }
    }

    private static byte[] hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Sign in could not be checked.", exception);
        }
    }

    private static String encode(String value) {
        return encode(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
