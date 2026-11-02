package in.munimsaathi.interpretation;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class WavAudio {

    static final String TOO_LONG = "Keep voice notes to 2 minutes or less. Nothing was transcribed.";
    private static final int MAX_INPUT_BYTES = 15 * 1024 * 1024;
    private static final int MAX_SECONDS = 120;

    private WavAudio() {
    }

    static byte[] toWav(byte[] audio, String mediaType) {
        if (audio == null || audio.length == 0 || audio.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("The voice note is empty or larger than the upload limit.");
        }
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if (type.startsWith("audio/wav") && supportedWav(audio)) {
            return audio;
        }
        Path input = null;
        Path output = null;
        Process process = null;
        try {
            String suffix = type.contains("ogg") ? ".ogg" : type.contains("mpeg") ? ".mp3" : ".bin";
            input = Files.createTempFile("note-", suffix);
            output = Files.createTempFile("note-", ".wav");
            Files.write(input, audio);
            process = new ProcessBuilder(
                    "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", input.toString(), "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                    // Decode one extra second to reject long clips, rather than silently accept a shortened command.
                    "-t", String.valueOf(MAX_SECONDS + 1), "-map_metadata", "-1", output.toString()
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                throw new IllegalArgumentException("The voice note could not be converted.");
            }
            if (process.exitValue() != 0) {
                throw new IllegalArgumentException("The voice note could not be converted.");
            }
            byte[] wav = Files.readAllBytes(output);
            if (!supportedWav(wav)) {
                throw new IllegalArgumentException("The voice note could not be converted.");
            }
            return wav;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalArgumentException("The voice note could not be converted.", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            delete(input);
            delete(output);
        }
    }

    private static boolean supportedWav(byte[] audio) {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audio))) {
            AudioFormat format = stream.getFormat();
            long frames = stream.getFrameLength();
            if (frames > 0 && format.getFrameRate() > 0 && frames / (double) format.getFrameRate() > MAX_SECONDS) {
                throw new IllegalArgumentException(TOO_LONG);
            }
            return frames > 0
                    && AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                    && format.getChannels() == 1
                    && format.getSampleSizeInBits() == 16
                    && (format.getSampleRate() == 16000 || format.getSampleRate() == 24000)
                    && !format.isBigEndian();
        } catch (UnsupportedAudioFileException | IOException exception) {
            return false;
        }
    }

    private static void delete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A temporary file left behind is not a voice result.
        }
    }
}
