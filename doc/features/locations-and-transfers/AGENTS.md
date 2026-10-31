# Locations and transfers

Milestone 10. A label can mean a product without becoming a debt, a godown can be remembered without a purchase, and a transfer keeps the business total the same.

## What this covers

- “Agarwal box” resolves to SKU A only after that alias is approved. A customer also named Agarwal stays a different party, and approving the alias does not create a receivable.
- Remembering godown 3 stores a location and does not create stock or a purchase.
- Receiving 1 A and 5 G, then selling 1 A and 3 G, leaves A at 0 and G at 2 in godown 3. Transferring 1 G to godown 1 leaves 1 G in each godown. Replaying the transfer does not move it again.
- Lookup of sold-out A reports godown 3 as history with nothing available there.
- A customer-owned box can sit in godown 3. It is not part of sellable stock, and a sale cannot take it.
- A transfer stays inside one business. Moving more than the godown holds changes neither godown.

## Spec

`doc/PRD.md` AC55–AC58. `doc/RFC-001.md` location facts, transfers, and the wholesale location fixture in sections 7 and 17. AC59 and AC60, the multi-item voice batch and a later invoice on a stock-only movement, stay open.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=LocationTransferTest test
```

## Still open

A transfer across businesses is rejected. The same journey in a second business is the two-tenant demo, later.
