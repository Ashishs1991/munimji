package in.munimsaathi.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

public class DatabaseUrlEnvironment implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty("DATABASE_URL");
        if (raw == null || raw.isBlank()) {
            return;
        }
        JdbcUrls.Parsed parsed = JdbcUrls.splitCredentials(raw);
        if (parsed.jdbcUrl() == null || parsed.jdbcUrl().isBlank()) {
            return;
        }
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("DATABASE_URL", parsed.jdbcUrl());
        if (parsed.username() != null && !parsed.username().isBlank()) {
            String existingUser = environment.getProperty("DATABASE_USERNAME");
            if (existingUser == null || existingUser.isBlank()) {
                overrides.put("DATABASE_USERNAME", parsed.username());
            }
        }
        if (parsed.password() != null) {
            String existingPassword = environment.getProperty("DATABASE_PASSWORD");
            if (existingPassword == null || existingPassword.isBlank()) {
                overrides.put("DATABASE_PASSWORD", parsed.password());
            }
        }
        environment.getPropertySources().addFirst(new MapPropertySource("jdbc-database-url", overrides));
    }
}
