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
    void aConfiguredClientPostsTheDocumentedTranscriptionContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://meta.example/asr/transcribe"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("muse-voice-transcribe-1.0")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PUSH_TO_TALK")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("WAV")))
                .andRespond(withSuccess("{\"transcript\":\"paanch box\"}", MediaType.APPLICATION_JSON));
        MetaTranscriber transcriber = new MetaTranscriber(properties(), builder, new ObjectMapper());

        assertThat(transcriber.transcribe(wav(), "audio/wav")).isEqualTo("paanch box");
        server.verify();
    }

    @Test
    void aNoteThatIsNotWavFailsConversionBeforeAnyRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MetaTranscriber transcriber = new MetaTranscriber(properties(), builder, new ObjectMapper());

        assertThatThrownBy(() -> transcriber.transcribe(new byte[] {1, 2, 3}, "audio/ogg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The voice note could not be converted.");
        server.verify();
    }

    @Test
    void aMissingKeyDoesNotCallTheModel() {
        MetaTranscriber transcriber = new MetaTranscriber(
                new MunimProperties("Asia/Kolkata", "INR", new MunimProperties.Meta("", "", "muse-spark-1.3", "muse-voice-transcribe-1.0"), null, null, null, null, null),
                RestClient.builder(),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> transcriber.transcribe(wav(), "audio/wav"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static byte[] wav() {
        byte[] wav = new byte[44];
        wav[0] = 'R';
        wav[1] = 'I';
        wav[2] = 'F';
        wav[3] = 'F';
        wav[8] = 'W';
        wav[9] = 'A';
        wav[10] = 'V';
        wav[11] = 'E';
        return wav;
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
