# Returns and corrections

Milestone 9. A posted sale stays as it was written. A return is a linked credit, and cash given back is a separate refund.

## What this covers

- The original sale amount and quantity stay unchanged after a return or a refund.
- A return increases sellable stock only when restock is approved. A damaged return posts the credit and leaves stock where it was.
- The credit reduces what the customer owes. It does not pay cash. A customer who has already paid can hold a credit balance.
- A cash refund is its own document. It does not move stock, and it does not change the credit. Refunding more than that credit balance is refused.
- Each return and refund stores a reason and the approver. Replaying the same operation does not post again.

## Spec

`doc/PRD.md` AC28–AC31. `doc/RFC-001.md` customer balance, including credits and explicitly recorded refunds, in section 7. AC32 stays open until an external export exists.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=ReturnCorrectionTest,OfficeFolderFixtureTest test
```

## Still open

A supplier credit note is not recorded here. External systems are not told about the correction.
