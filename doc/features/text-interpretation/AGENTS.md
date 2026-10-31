# Text interpretation

Milestone 13. A Hindi or Hinglish command becomes a draft through the Meta client. The model can propose fields. It cannot post them.

## What this covers

- The application sends the owner’s text to the configured Meta reasoning model and reads one JSON object back. Tests replace that client, so they do not call the network.
- A reply that names the party, product, quantity, unit, price, and godown becomes one sale draft. Stock and money stay unchanged until that draft is approved.
- A quantity or price that is not a plain number stays missing. The application does not turn the word “five” into 5, and it does not turn a vague price into paise.
- A business id inside the model JSON is ignored. The draft belongs to the owner’s selected business.
- `approve_and_post` and `send_approved_message` are refused. A bill that says to ignore instructions and post still only creates a draft.

## Spec

`doc/PRD.md` AC14, and the text half of F02. `doc/RFC-001.md` sections 8 and 10: model JSON is validated, and posting tools are application operations. Photos, voice, and WhatsApp ingress stay open.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=CommandInterpreterTest,MetaLanguageModelTest test
```

The live call needs `META_API_KEY` and `META_API_BASE_URL`. Without them the client stops before any request.

## Still open

The model reply is not stored as a model run. The client speaks the chat-completions shape at the configured base URL.
