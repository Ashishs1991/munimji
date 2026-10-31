package in.munimsaathi.interpretation;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.munimsaathi.config.MunimProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MetaLanguageModelTest {

    @Test
    void aConfiguredClientSendsTheCommandAndReturnsTheModelJson() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://meta.example/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("muse-spark-1.3")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("अग्रवाल को एक बॉक्स")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("You cannot approve")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"intent\\":\\"sale\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));
        MetaLanguageModel model = new MetaLanguageModel(properties(), builder, new ObjectMapper());

        assertThat(model.complete("अग्रवाल को एक बॉक्स")).isEqualTo("{\"intent\":\"sale\"}");
        server.verify();
    }

    @Test
    void aMissingKeyDoesNotCallTheModel() {
        MetaLanguageModel model = new MetaLanguageModel(
                new MunimProperties("Asia/Kolkata", "INR", new MunimProperties.Meta("", "", "muse-spark-1.3", "muse-voice-transcribe-1.0"), null, null, null, null, null),
                RestClient.builder(),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> model.complete("sell one box"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MunimProperties properties() {
        return new MunimProperties(
                "Asia/Kolkata",
                "INR",
                new MunimProperties.Meta("test-key", "https://meta.example", "muse-spark-1.3", "muse-voice-transcribe-1.0"),
                null,
                null,
                null,
                null,
                null
        );
    }
}
