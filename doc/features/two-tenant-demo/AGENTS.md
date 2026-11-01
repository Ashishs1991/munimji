# Two-tenant demo path

Milestone 21. Receive, locate, sell, correct, and remind run in both businesses. An Agarwal in one house is not the Agarwal in the other. The recorded stock and balances stay on the business that posted them.

## What this covers

- A shared owner must choose the active business before any of that work.
- In City Stores the path matches the recorded fixture: receive 1 Agarwal box and 5 Gupta boxes in godown 3, sell 1 and 3, transfer 1 Gupta box. Agarwal stock is 0. Gupta is 1 in godown 3 and 1 in godown 1. The customer owes ₹400. A replayed transfer does not move stock again.
- A return of 1 Gupta box restocks godown 3 and reduces the balance to ₹300. Repeating that approval does not do it twice.
- Other House receives 2 Agarwal boxes, sells 1, and is reminded on its own task. Its Agarwal is a different party. Its stock stays 1 box there and does not appear in City Stores.
- The reminder job keeps the business it was scheduled in after the owner switches. Dispatch of that debt-linked reminder uses the saved business, even when the owner is currently in the other one.

## Spec

`doc/PRD.md` AC61–AC63. `doc/RFC-001.md` section 17, the wholesale location fixture.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=TwoTenantDemoTest test
```

## Still open

Tally is left for a later decision. Sign-in is still an application user id.
