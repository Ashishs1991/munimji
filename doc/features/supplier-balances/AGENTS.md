# Supplier balances

Milestone 8. An approved purchase bill raises what the business owes the supplier. A goods receipt does not invent that price. Customer and supplier balances stay on separate sides.

## What this covers

- Receiving goods leaves the supplier payable at zero. Recording the bill still leaves it at zero until the bill is approved onto the payable.
- Approving the bill adds the bill amount once. Replaying that approval does not add it again, and it does not change stock.
- A supplier payment can be a part of the bill. Paying more than the remaining payable is refused.
- A supplier payment with no bill stays unapplied. The payable falls, and the bill outstanding does not.
- A party who is both customer and supplier has two balances. Paying the supplier does not reduce what they owe as a customer, and taking their payment does not reduce what the business owes them.

## Spec

`doc/PRD.md` AC79–AC81. `doc/RFC-001.md` supplier payables and the rule against netting receivable and payable, in section 7. AC82 waits for the dashboard.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=SupplierBalanceTest test
```

## Still open

A supplier credit note is not a payment. Recording one, and returns that reverse a sale, are later work. A supplier payment is owner-reported. It does not move money through a bank.
