# Voice receipts

Milestone 15. A voice note is transcribed into one draft that keeps every line. Five boxes stay boxes until a pack conversion is already approved.

## What this covers

- The audio bytes are stored with the draft. Tests use a fake transcriber and a fake model, so they do not call Meta.
- The transcript becomes one sale draft with a line for every item the model returned. An unresolved line stays on that draft.
- Five boxes of a product with no approved box conversion stay as quantity 5 and unit box. They are not stored as five pieces, and approval does not post them.
- When the box conversion is already approved, approving the draft posts every line once. Five boxes of ten pieces leave none of those pieces. Approving again does not post again.
- If a later line cannot be posted, the earlier line is not posted either.

## Spec

`doc/PRD.md` AC09, AC59, and AC60’s conversion rule. `doc/RFC-001.md` section 7: one multi-line statement commits together or not at all. WhatsApp delivery of the voice note stays open.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=VoiceReceiptTest,MetaTranscriberTest test
```

## Still open

The transcript client posts to `{META_API_BASE_URL}/audio/transcriptions`. A missing key stops before that call.
