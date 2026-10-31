# Reminders and sends

Milestone 17. An owner reminder and a customer send are different tasks. A customer send goes out only when the approved balance is still the same, the task was not canceled, and the provider returns a status.

## What this covers

- Scheduling an owner reminder does not message the customer. A customer send names a consenting contact, a template, and an existing receivable.
- Linking that receivable does not increase it. A note-only reminder stays out of the balance.
- 17 November 2026 at 10:00 is stored as that Kolkata time. A missing time is refused.
- Full payment suppresses the send. A smaller balance waits for a new approval. Canceling before dispatch leaves the provider uncalled. A due time more than 15 minutes late is missed.
- A send stores the provider status and id from the reply. Accepted and failed stay distinct. A missing token stays blocked and does not mark the message delivered. An attempt left in sending is not sent again.

## Spec

`doc/PRD.md` AC39–AC43 and AC75–AC78. `doc/RFC-001.md` section 14A. The November 17 example is date parsing, not a message created by the spec.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=ReminderTest,WhatsAppMessageSenderTest test
```

## Still open

No live WhatsApp message was sent from this suite. A real delivery needs `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_GRAPH_API_VERSION`, and a consenting contact. The grace window is `REMINDER_GRACE_MINUTES`, default 15.
