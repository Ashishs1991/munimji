# Sales and receipts

Milestone 7. A sale takes stock once, the invoice amount stays separate from money received, and a canceled invoice number is never issued again.

## What this covers

- A sale deducts stock once and refuses to go negative. Replaying it does not deduct again or take a second invoice number.
- Each business numbers its own invoices from 1. Canceling an invoice retires that number. The next invoice takes the next number. Canceling does not undo the stock movement or the amount owed.
- Money received against an invoice is allocated to that invoice. Money received with no invoice stays unapplied: the customer balance falls, and the invoice outstanding does not.
- A receipt stores the payment method the owner reported. That is not a bank confirmation.
- Two customers with the same display name keep separate balances.

## Spec

`doc/PRD.md` AC21–AC24 and AC26. `doc/RFC-001.md` customer balance, invoice numbering, and unapplied receipts in sections 7 and 17. AC25 and AC27 stay open: these numbers are operational, not tax invoices, and a receipt is not bank-verified.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=SalesReceiptTest,OfficeFolderFixtureTest test
```

## Still open

Canceling a number does not reverse the sale. Supplier payables are the next milestone.
