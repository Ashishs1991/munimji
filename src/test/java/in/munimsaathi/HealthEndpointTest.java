package in.munimsaathi;

import in.munimsaathi.config.MunimProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MunimProperties properties;

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void nonSecretDefaultsComeFromApplicationYaml() {
        assertThat(properties.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(properties.currency()).isEqualTo("INR");
        assertThat(properties.meta().reasoningModel()).isEqualTo("muse-spark-1.3");
        assertThat(properties.meta().transcriptionModel()).isEqualTo("muse-voice-transcribe-1.0");
        assertThat(properties.tally().enabled()).isFalse();
        assertThat(properties.eway().enabled()).isFalse();
        assertThat(properties.eway().environment()).isEqualTo("sandbox");
    }
}
