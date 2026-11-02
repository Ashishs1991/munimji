# Demo rehearsal — 2 November 2026

The recovery code and its regression tests are implemented. The local PostgreSQL suite and Docker audio-conversion smoke check passed. Deployment and a real WhatsApp recording remain the final checks.

## Before deployment

- Include all recovery source files and `V24__money_actions.sql` in Git. A tracked caller with untracked dependencies is not deployable from a fresh checkout.
- Use an isolated demo business with approved product aliases, units, parties and godowns. Preserve real records; do not reset production balances to prepare a recording.
- Confirm the deployed Meta key can list the needed models. An empty `/models` result or `model_not_found` remains a provider/configuration blocker, regardless of passing local tests.
- Verify WhatsApp sender approval, access token, app secret, owner mapping and templates needed for any scheduled customer send. Keep optional e-way submission disabled.
- Run Flyway normally. Do not manually rewrite previously applied migrations or use Hibernate schema updates.

## Rehearse on the deployed build

1. Start an incomplete sale for two boxes. Send a separate `Rahul se 1000 received` message. The sale remains intact; the assistant starts a money action and asks only for missing money/party details.
2. With a verified synthetic Rahul receivable of ₹1,500, approve ₹1,000 received via the stated payment method. The confirmation must explicitly say money was received, the resulting receivable is ₹500, and stock is unchanged. A repeated approval must not post twice.
3. Ask about an already recorded Agarwal debt. It must not create another debt. Explicit new lending and supplier obligations must state opposite directions in their confirmations.
4. Send a real voice note for a payment, then a text follow-up such as the payment method. Send a voice balance query too. The transcript and reply must remain available to later context.
5. Continue past six owner/assistant exchanges. Confirm the latest question is retained and a short answer fills the intended field.
6. Say `रद्द करो` with one open draft. Check it is actually canceled. With several drafts, the assistant must ask which one. A canceled draft must not remain approvable.
7. Review one goods receipt and sale: every product, quantity, unit and godown must match the independently prepared expected stock.
8. Confirm unknown party handling, a stale approval, and switching between demo businesses do not create duplicates or mix records.
9. Any reminder/forecast shown in the video must be verified independently. Do not describe a scheduled message as delivered until the real provider status supports it. Keep these outside the recording if they have not been rehearsed.

## Recording

Use one coherent three-minute owner journey. Show the actual record changes and a correction/clarification, not a list of features. Clearly identify synthetic data. Keep keys, personal phone numbers and private documents out of the recording.

Do not call this production-ready based on the rehearsal. Deployment, provider availability and real speech accuracy are separate from the local automated checks.
