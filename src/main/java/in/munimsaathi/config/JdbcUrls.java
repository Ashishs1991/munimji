package in.munimsaathi.config;

public final class JdbcUrls {

    private JdbcUrls() {
    }

    public static String normalize(String url) {
        if (url == null || url.isBlank() || url.startsWith("jdbc:")) {
            return url;
        }
        if (url.startsWith("postgres://")) {
            return "jdbc:postgresql://" + url.substring("postgres://".length());
        }
        if (url.startsWith("postgresql://")) {
            return "jdbc:" + url;
        }
        return url;
    }
}
