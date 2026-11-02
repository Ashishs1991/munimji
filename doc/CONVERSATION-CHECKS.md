# Conversation checks before the demo

Restart the local application to apply V29. Do not repeat already-posted loan commands merely to test the fix.

## Reopen the existing plastic-box draft

Send: `entry <pending-draft-id>`

Replace the placeholder with the draft ID shown by the bot. If this draft is still pending/unexpired, the bot should offer to create **plastic boxes** in **bori** while retaining **5 bori**, **Anil bhai**, and **Godown 1**. Haan creates the offered product. Review the next full stock proposal; a separate Haan posts it. If the earlier stock interpretation was incorrect, cancel this draft instead of approving it.

## Use these synthetic examples for a fresh test tenant

| Owner says | Expected |
|---|---|
| Anil bhai ko 10000 cash udhaar diye | New-party offer if unknown, then a reviewed outgoing loan; never a payable |
| Haan, while asked to create Anil | Creates only the offered party; no loan posted yet |
| Haan nahi party hai Anil | Asks new vs existing; creates nothing |
| Anil bhai ka maal Godown 1 mein rakha hai | Location-note proposal; no product/quantity/price required; no stock/ledger change |
| Anil bhai ka maal kahan hai? | Last noted location, not a claim of available stock |
| Gupta se 5 bori Plastic boxes liye, Godown 1 mein | Receipt draft; unknown entities offered for creation; quantities/location retained |
| Product - Plastic boxes | Acknowledge name; ask specifically whether to register it, not which product again |
| Haan to product creation, then Haan to stock proposal | Separate metadata and stock approvals; repeating final approval does not add stock again |
| Plastic boxes ki 5 bori, with no clear action | Ask arrived/sold/location; do not invent a purchase |
| Balance query after a sale proposal, then Yes | No old sale posted; reopen/review it before approval |

## Run real-model evaluation yourself

With Docker running, from the repo:

```sh
python3 scripts/check-live-conversations.py
```

This makes real Meta inference requests using the app prompt and synthetic data. All database effects are in disposable PostgreSQL; it does not send WhatsApp messages. The default suite skips it. Share the test summary/failure, not .env or your API key.

## Inspect future model decisions (read-only)

```sql
SELECT created_at AT TIME ZONE 'Asia/Kolkata' AS time_ist,
       source_text, model_reply, resolved_kind, draft_id
FROM interpretation_turns
WHERE business_id = '<business-id>'
ORDER BY created_at DESC
LIMIT 15;
```

The trace is populated only after V29 is installed. A real voice retest is still needed to assess transcription quality.
