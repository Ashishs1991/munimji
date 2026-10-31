package in.munimsaathi;

import in.munimsaathi.config.MunimProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DotenvBindingTest {

    @Test
    void applicationYamlReadsMetaApiKeyFromDotEnvFile() throws Exception {
        Path envFile = Path.of(".env");
        Assumptions.assumeFalse(Files.exists(envFile), ".env already exists; refusing to overwrite local secrets");

        Files.writeString(envFile, "META_API_KEY=from-dotenv-file\n");
        try (ConfigurableApplicationContext context =
                     SpringApplication.run(MunimSaathiApplication.class, "--server.port=0")) {
            assertThat(context.getBean(MunimProperties.class).meta().apiKey()).isEqualTo("from-dotenv-file");
        } finally {
            Files.deleteIfExists(envFile);
        }
    }
}
