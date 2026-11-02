# Demo conversation recovery

Updated 2 November 2026. Implements the recovery plan for voice, money actions and context, plus the follow-up review corrections.

## Implemented and checked

- Money received, standalone receivables and supplier obligations use distinct approved actions; stock is unaffected by money-only entries.
- A new money action does not overwrite an unrelated pending stock transaction. Missing parties and payment methods are clarified.
- The latest ten saved conversation messages are supplied in chronological order. Voice transcripts are saved as owner turns.
- Natural-language cancellation resolves an open draft in the current business, asks when ambiguous, and never claims success for a nonexistent target.
- Confirmation text distinguishes money received, money owed to the owner, and supplier payables. It describes stock and allocation effects before approval.
- Transcription uses `/asr/transcribe`, multipart settings/audio and the `transcript` response. ffmpeg is included in Docker.
- Supported mono 16-bit PCM WAV is validated; other inputs are converted. Notes over two minutes are rejected rather than silently shortened. Conversion is bounded and temporary files are removed.

## Verification

`./mvnw test`: 159 tests, zero failures/errors/skips in an isolated copy without live credentials. Includes the earlier failing memory, voice-history and Hindi-cancellation regressions.

`docker build -t munim-saathi-demo-check:local .`: Java 17 image build passed. A generated-audio smoke check inside this image passed actual OGG/Opus conversion, 48 kHz stereo WAV normalization to supported mono PCM, and rejection of a 121-second OGG clip. No live speech-to-text call was used for this check.

Targeted tests:

```bash
./mvnw -Dtest=DemoRecoveryTest,WhatsAppTurnTest,MetaTranscriberTest,WavAudioTest test
```

## Demo gates

Follow `doc/DEMO-CHECKLIST.md`. Real Meta inference and WhatsApp delivery must be rehearsed on the deployed build. Automated mocks establish application behaviour, not provider interoperability or vernacular accuracy. The optional e-way adapter remains disabled pending its documented prerequisites. Do not add unrelated features before the demo.
