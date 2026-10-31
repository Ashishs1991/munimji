# Opening stock

Milestone 4. A confirmed physical count sets stock once. Repeating that confirmation does not add the quantity again. A later count adjusts to the new quantity.

## What this covers

- A count session records the godown, the effective time, and each proposed line.
- Confirmation converts the approved pack size into base units and stores that factor on the movement.
- The same operation id is a no-op. A new count session posts an adjustment, not a second opening.
- If stock changes after a line is proposed, confirmation stops until the count is taken again.
- Unknown expiry stays null. A readable expiry is stored as that date on a batch.
- Progress reports confirmed lines and unresolved lines.
- Stock cannot go below zero.

## Spec

`doc/PRD.md` AC02 and AC04–AC08. `doc/RFC-001.md` count sessions, stock movements, and stock balances in section 6. AC03, about historical bills not adding stock on top of an opening snapshot, belongs to the import milestone.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=OpeningStockTest test
```

## Still open

Purchases, sales, receipts, and the office-folder money fixture are the next milestone. Customer-owned stock is not counted as sellable here.
