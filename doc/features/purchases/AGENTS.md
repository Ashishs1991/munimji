# Purchases

Milestone 6. A supplier bill and a goods receipt keep their own quantities. Stock rises only by what was received.

## What this covers

- Receiving goods with no price posts stock and leaves finance pending. It does not invent a payable.
- A later supplier bill records its own quantity and amount and links to that receipt. It does not add stock.
- Billing stops when the billed quantity would pass the quantity received. A partial bill leaves the receipt partially billed.
- The same supplier, bill number, and period is held for review. The same supplier, period, product, quantity, and amount is also held for review, even under another bill number.
- The same bill number in another business is a different bill. Replaying a posted bill does nothing.

## Spec

`doc/PRD.md` AC15 and AC17–AC18. `doc/RFC-001.md` goods receipt with finance pending, and separate billed and received quantities, in section 7. AC16, AC19, and AC20 stay open: tax finalization and the optional Tally export are later work.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=PurchaseTest test
```

## Still open

An approved bill does not yet raise a supplier payable. That is the next milestone.
