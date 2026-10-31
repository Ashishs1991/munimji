# Drafts and approval

Milestone 11. A proposal stays a draft until the owner approves the current version. Missing fields stay missing.

## What this covers

- A sale proposal with quantity, party, product, unit, location, date, and price posts only when that version and hash are approved. Stock and the receivable stay unchanged before approval. Approving the same version again does not post twice.
- Missing quantity, party, unit, action, or price is recorded as missing. The proposal does not fill any of those in.
- A missing price, with the inventory fields present, can be approved as a dispatch. Stock falls, the document total stays empty, finance stays pending, and no receivable or invoice number is created.
- “Yes” approves only when exactly one current proposal is awaiting approval. Two pending proposals post nothing.
- Editing a proposal bumps its version and hash. The previous approval posts nothing. The new version posts the edited quantity.

## Spec

`doc/PRD.md` AC11–AC13, including the stock-only path in AC12. `doc/RFC-001.md` sections 8 and 9: a draft version and content hash, one posting approval per version, and a bare “yes” only when one current proposal is pending. AC10, AC14, AC59, and AC60 stay open.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=DraftApprovalTest test
```

## Still open

A draft here is one sale line. Photos, voice notes, Hindi commands, and a later invoice on a stock-only dispatch are later milestones. Canceling an unposted draft is not in this slice.
