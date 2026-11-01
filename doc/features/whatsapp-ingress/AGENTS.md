# WhatsApp ingress

Milestone 20. A signed webhook is stored and acknowledged before any model call. Unknown senders are rejected. A duplicate event does not open a second draft.

## What this covers

- `GET /webhooks/whatsapp` answers the verify challenge when the token matches.
- `POST /webhooks/whatsapp` checks `X-Hub-Signature-256` against the raw body and `WHATSAPP_APP_SECRET`. A bad signature is not stored.
- A known sender's message is stored and acknowledged in that request. The language model is not called until a later open-draft step.
- An unknown phone is stored as rejected. Sending the same message id again does not add a row.
- A delivery status is stored as a status callback and is not turned into a command.
- Opening drafts from stored messages creates one draft per message. Running it again keeps that draft.
- With `WHATSAPP_AUTO_REPLY=true`, a stored message is answered in the WhatsApp session window after the webhook has already returned. Only an accepted provider receipt marks the inbound message replied. A blocked, failed, or unknown send stays pending.
- `Yes` is bound to the one awaiting draft from that attempt, using the version and hash shown to the owner. An edit after that version was shown is rejected. Posting is saved before the reply is sent. A retry sends the reply again and does not approve a later draft.
- Each stored message is interpreted in the business saved on that message. Switching the owner's current business before processing does not move the draft.
- A follow-up fills the single open draft. The reply names the party, product, quantity, and godown before asking for Yes.
- A voice note, photo, or PDF is downloaded and passed to the voice or document flow. Purchase, location, balance, and reminder intents are read from the model JSON. `Yes` can approve one owner reminder. That reminder is listed on the dashboard and is not sent to the customer.
- A phone in `WHATSAPP_OWNER_PHONE` is registered to one new business on an empty database. That startup does not add stock.

## Spec

`doc/PRD.md` F02 ingress and the duplicate-event rule. `doc/RFC-001.md` section 9: verify, persist, acknowledge, and do not wait on the model.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=WhatsAppIngressTest,WhatsAppTurnTest,WhatsAppSessionClientTest test
```

## Still open

The suite uses the configured app secret and does not call Meta. A live WhatsApp number still has to be connected in the Meta console. Dashboard and e-way HTTP calls require a signed-in owner token; the webhook stays signed by the app secret and is not a user session.
