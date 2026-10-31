# Posting core

Milestone 5. The office-folder fixture posts stock and customer paise once. Replaying the same approvals leaves 62 pieces and ₹400 outstanding.

## What this covers

- An opening count of 5 boxes is 50 pieces and no customer balance.
- Receiving 2 boxes records a purchase and raises stock to 70. It does not create a customer receivable or invent a price.
- Selling 1 box for ₹1,000 leaves 60 pieces and 100000 paise receivable. A sale that would go negative is refused and changes nothing.
- Receiving ₹400 against that sale leaves the stock at 60 and the receivable at 60000 paise. The receipt is allocated to the sale.
- Returning 2 resellable pieces with a ₹200 credit leaves 62 pieces and 40000 paise. A damaged return posts the credit and does not put the pieces back into sellable stock.
- Money is integer paise. Repeating any of these operation ids does not post a second stock movement or ledger entry.

## Spec

`doc/PRD.md` AC21, AC22, AC29, and AC30. `doc/RFC-001.md` customer balance, the local posting algorithm, and the office-folder fixture in sections 7 and 17.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=OfficeFolderFixtureTest,PaiseTest test
```

## Still open

Supplier bills, invoice numbers, unallocated receipts, and cash refunds are later milestones. A purchase still has no payable.
