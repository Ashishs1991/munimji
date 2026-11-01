package in.munimsaathi.config;

import java.net.URI;
import java.net.URISyntaxException;

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
        String forUri = normalized.substring("jdbc:".length());
        URI uri;
        try {
            uri = new URI(forUri);
        } catch (URISyntaxException exception) {
            return new Parsed(normalized, null, null);
        }
        String userInfo = uri.getUserInfo();
        if (userInfo == null || userInfo.isBlank()) {
            return new Parsed(normalized, null, null);
        }
        String username;
        String password;
        int separator = userInfo.indexOf(':');
        if (separator < 0) {
            username = userInfo;
            password = null;
        } else {
            username = userInfo.substring(0, separator);
            password = userInfo.substring(separator + 1);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return new Parsed(normalized, username, password);
        }
        StringBuilder rebuilt = new StringBuilder("jdbc:postgresql://").append(host);
        if (uri.getPort() > 0) {
            rebuilt.append(':').append(uri.getPort());
        }
        if (uri.getPath() != null) {
            rebuilt.append(uri.getPath());
        }
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            rebuilt.append('?').append(uri.getQuery());
        }
        return new Parsed(rebuilt.toString(), username, password);
    }
}
