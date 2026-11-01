package in.munimsaathi.config;

import java.nio.charset.StandardCharsets;

public final class JdbcUrls {

    private JdbcUrls() {
    }

    public record Parsed(String jdbcUrl, String username, String password) {
    }

    public static String trimEnv(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    public static String normalize(String url) {
        String trimmed = trimEnv(url);
        if (trimmed == null || trimmed.isBlank() || trimmed.startsWith("jdbc:")) {
            return trimmed;
        }
        if (trimmed.startsWith("postgres://")) {
            return "jdbc:postgresql://" + trimmed.substring("postgres://".length());
        }
        if (trimmed.startsWith("postgresql://")) {
            return "jdbc:" + trimmed;
        }
        return trimmed;
    }

    public static Parsed splitCredentials(String jdbcUrl) {
        String normalized = normalize(jdbcUrl);
        if (normalized == null || normalized.isBlank() || !normalized.startsWith("jdbc:postgresql://")) {
            return new Parsed(normalized, null, null);
        }
        String rest = normalized.substring("jdbc:postgresql://".length());
        int at = rest.lastIndexOf('@');
        if (at <= 0) {
            return new Parsed(normalized, null, null);
        }
        String userInfo = rest.substring(0, at);
        String location = rest.substring(at + 1);
        if (userInfo.indexOf('/') >= 0 || userInfo.indexOf('?') >= 0 || userInfo.indexOf('#') >= 0
                || userInfo.indexOf('@') >= 0) {
            return new Parsed(normalized, null, null);
        }
        int separator = userInfo.indexOf(':');
        if (separator <= 0 || separator == userInfo.length() - 1) {
            return new Parsed(normalized, null, null);
        }
        if (!location.matches("[A-Za-z0-9._-]+:[0-9]+/[^?#]+(\\?[^#]*)?")) {
            return new Parsed(normalized, null, null);
        }
        String username = decode(userInfo.substring(0, separator));
        String password = decode(userInfo.substring(separator + 1));
        if (username == null || username.isBlank() || password == null) {
            return new Parsed(normalized, null, null);
        }
        return new Parsed("jdbc:postgresql://" + location, username, password);
    }

    private static String decode(String value) {
        byte[] bytes = new byte[value.length()];
        int length = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()) {
                    return null;
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    return null;
                }
                bytes[length++] = (byte) ((high << 4) + low);
                index += 2;
            } else if (current > 127) {
                return null;
            } else {
                bytes[length++] = (byte) current;
            }
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
