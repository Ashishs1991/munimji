package in.munimsaathi.interpretation;

import org.junit.jupiter.api.Test;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WavAudioTest {
    @Test void acceptsRealSupportedWavWithoutConversion() {
        byte[] audio = wav(16000, 1, 1600);
        assertThat(WavAudio.toWav(audio, "audio/wav")).isEqualTo(audio);
    }

    @Test void refusesLongWavInsteadOfDroppingTheEnd() {
        assertThatThrownBy(() -> WavAudio.toWav(wav(16000, 1, 121 * 16000), "audio/wav"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(WavAudio.TOO_LONG);
    }

    @Test void rejectsAnEmptyNote() {
        assertThatThrownBy(() -> WavAudio.toWav(new byte[0], "audio/wav"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static byte[] wav(int sampleRate, int channels, int frames) {
        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(new byte[frames * channels * 2]), format, frames);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
