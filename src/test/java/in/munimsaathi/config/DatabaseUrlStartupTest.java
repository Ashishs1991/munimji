package in.munimsaathi.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUrlStartupTest {

    @Test
    void startupLoadsTheHookAndRewritesARailwayUrl() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(
                EnvironmentPostProcessor.class,
                DatabaseUrlStartupTest.class.getClassLoader()
        )).contains(DatabaseUrlEnvironment.class.getName());

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "railway",
                Map.of("DATABASE_URL", "postgresql://postgres:secret@db.railway.internal:5432/railway")
        ));
        new DatabaseUrlEnvironment().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("DATABASE_URL"))
                .isEqualTo("jdbc:postgresql://postgres:secret@db.railway.internal:5432/railway");
    }
}
