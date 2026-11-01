package in.munimsaathi.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

public class DatabaseUrlEnvironment implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("DATABASE_URL");
        String jdbc = JdbcUrls.normalize(url);
        if (jdbc != null && !jdbc.equals(url)) {
            environment.getPropertySources().addFirst(new MapPropertySource("jdbc-database-url", Map.of("DATABASE_URL", jdbc)));
        }
    }
}
