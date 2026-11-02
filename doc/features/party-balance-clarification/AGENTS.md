# Party-name follow-ups for balances

Updated 2 November 2026. Fixes the Anisha payable clarification reported during the WhatsApp rehearsal.

## What changed

- A balance lookup that cannot resolve its party now saves a typed pending question in PostgreSQL, keyed by business and actor and expiring after two hours. The party, direction and mentioned amount survive a party-name-only follow-up.
- Payables query supplier records; receivables query customer records. A party with both roles returns both balances if the direction is unknown. Prompts describe Hindi/Hinglish money direction explicitly.
- Unknown parties produce a follow-up with the remembered balance question, not a greeting or a stock form. Explicit creation can add a party with its confirmed supplier/customer role; it never creates the mentioned debt by itself.
- If a mentioned amount differs from the recorded balance, the bot asks whether it is new or existing. An explicit answer can prepare an approved money action with the saved party/direction/amount. Payable proposals now require their cause to be clarified too.
- Canceling an outstanding balance question clears that question without canceling an unrelated stock draft.
- Existing debt does not get added again. A new action clears an unrelated pending balance question; business switching cannot reuse another business's question.

## Regression checks

`DemoRecoveryTest` covers the exact two-turn Anisha exchange, an unknown party, the new/existing safeguard, explicit supplier creation followed by approved debt posting, and business isolation/new-action reset. All use synthetic records and scripted model replies; no live provider calls or business records are used.

```bash
./mvnw -Dtest=DemoRecoveryTest,CommandInterpreterTest,WhatsAppTurnTest test
```

Run Flyway normally to apply `V25__pending_balance_questions.sql`; do not edit already-applied migrations. The table is server-only with row-level security enabled.

## Live rehearsal

1. For a saved supplier, say “Anisha ko paanch hazaar dene hain,” then answer “Party Anisha Gajak.” The bot must retain the money direction and report the recorded payable; stock stays unchanged.
2. If the party is unknown, explicitly request creating the supplier only after reviewing the bot's question. Creating a party must not add ₹5,000 of debt.
3. Confirm whether a new amount is to be recorded, inspect its payable confirmation, and approve once. If it was already recorded, no new posting occurs.

Actual transcription and model classification need the deployed WhatsApp rehearsal; this change verifies application handling of those outcomes.
