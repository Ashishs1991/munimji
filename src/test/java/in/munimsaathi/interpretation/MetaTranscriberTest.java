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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MetaTranscriberTest {

    @Test
    void aConfiguredClientSendsTheVoiceNoteAndReturnsTheTranscript() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://meta.example/audio/transcriptions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("muse-voice-transcribe-1.0")))
                .andRespond(withSuccess("{\"text\":\"paanch box\"}", MediaType.APPLICATION_JSON));
        MetaTranscriber transcriber = new MetaTranscriber(properties(), builder, new ObjectMapper());

        assertThat(transcriber.transcribe(new byte[] {1, 2, 3}, "audio/ogg")).isEqualTo("paanch box");
        server.verify();
    }

    @Test
    void aMissingKeyDoesNotCallTheModel() {
        MetaTranscriber transcriber = new MetaTranscriber(
                new MunimProperties("Asia/Kolkata", "INR", new MunimProperties.Meta("", "", "muse-spark-1.3", "muse-voice-transcribe-1.0"), null, null, null, null),
                RestClient.builder(),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> transcriber.transcribe(new byte[] {1}, "audio/ogg"))
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
                null
        );
    }
}
