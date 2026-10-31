# Historical import

Milestone 19. One supported export is staged, mapped, and reconciled. Activation posts the opening snapshot once. The same file does nothing the second time. Historical sales stay out of live stock.

## What this covers

- The file is a CSV with the header `row_class,source_id,name,quantity,amount_paise`.
- Product and party names map only when the business already has exactly one match. A row without a source id stays quarantined and blocks activation.
- Activation posts the opening quantity through a stock count and the opening receivable through the ledger. Doing it again leaves both unchanged.
- A historical sale row is kept and is not posted, so live stock stays at the snapshot.
- Staging the same bytes returns the same import. A changed file is a new staged import and does not move live stock until it is activated.

## Spec

`doc/PRD.md` AC64–AC68. `doc/RFC-001.md` historical imports: evidence plus one reconciled opening snapshot. The fixture is 10 pieces and ₹5,000, with a historical sale that must not be replayed.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=HistoricalImportTest test
```

## Still open

Only this CSV shape is supported. Scans, Tally company exports, and late overlap review beyond staging a changed file are still open. An import does not schedule reminders.
