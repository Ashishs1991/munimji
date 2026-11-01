# WhatsApp ingress

Milestone 20. A signed webhook is stored and acknowledged before any model call. Unknown senders are rejected. A duplicate event does not open a second draft.

## What this covers

- `GET /webhooks/whatsapp` answers the verify challenge when the token matches.
- `POST /webhooks/whatsapp` checks `X-Hub-Signature-256` against the raw body and `WHATSAPP_APP_SECRET`. A bad signature is not stored.
- A known sender's message is stored and acknowledged in that request. The language model is not called until a later open-draft step.
- An unknown phone is stored as rejected. Sending the same message id again does not add a row.
- A delivery status is stored as a status callback and is not turned into a command.
- Opening drafts from stored messages creates one draft per message. Running it again keeps that draft.
- With `WHATSAPP_AUTO_REPLY=true`, a stored text message is answered in the WhatsApp session window after the webhook has already returned. `Yes` posts only the one current draft. A phone in `WHATSAPP_OWNER_PHONE` is registered to one new business on an empty database. That startup does not add stock.

## Spec

`doc/PRD.md` F02 ingress and the duplicate-event rule. `doc/RFC-001.md` section 9: verify, persist, acknowledge, and do not wait on the model.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=WhatsAppIngressTest,WhatsAppTurnTest,WhatsAppSessionClientTest test
```

## Still open

Media download, replies, and a live WhatsApp number are not wired. The suite uses the configured app secret and does not call Meta.
