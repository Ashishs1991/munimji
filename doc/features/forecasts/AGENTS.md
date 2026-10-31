# Forecasts

Milestone 18. A 7-day baseline uses covered sales. Thin history stays insufficient. A reorder waits for lead time and inbound stock.

## What this covers

- The owner marks the days when sales capture was complete. A missing day is unknown, not a zero.
- The baseline is the 28-day mean of gross sales times 7. It runs when those 28 days are covered and at least 8 of them had a sale. Otherwise the result is insufficient reliable history and has no forecast quantity.
- Opening counts, purchase receipts, and godown transfers are not demand. Returns are reported on their own and are not added to sales.
- With only 28 days the baseline is provisional. It does not claim a validated accuracy improvement.
- A reorder quantity is withheld when lead time or inbound stock is missing.

## Spec

`doc/PRD.md` AC70–AC74. `doc/RFC-001.md` section 14B. Holdout MAE and WAPE stay open until a product has 42 covered days.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=ForecastTest test
```

## Still open

There is no safety-stock policy, review period, or firm-commitment input yet. A reorder with those unknown is not offered. No purchase order is created.
