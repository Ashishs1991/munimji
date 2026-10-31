# Munim Saathi

Hindi-first business assistant for an Agra plastic-bag wholesaler. The product spec is `doc/PRD.md`. The technical design is `doc/RFC-001.md`. Follow both, and the rules in `.cursor/rules/`.

## How to continue

Work hands-off, one milestone at a time, in the order below. Do not scaffold empty modules for later milestones.

For each milestone:

1. Read the matching PRD acceptance criteria and RFC section before writing code.
2. Write the test first, then the implementation.
3. Keep secrets out of git. New API keys go in `.env.example` and `src/main/resources/application.yml` only.
4. Add `doc/features/<feature-slug>/AGENTS.md` for that feature.
5. Commit just that feature. The message must not contain the word Cursor.
6. Push the commit.

Stop at a failing test or a missing owner decision recorded in the feature `AGENTS.md`. Do not invent stock, money, tax, or a successful external send.

## Configuration

Copy `.env.example` to `.env` and fill in keys locally. The application reads them through `src/main/resources/application.yml`.

## Milestones

Milestone 0 is done. Build the next undone milestone only. The books come before the assistant: a model proposes a draft, and Java posts stock and money.

| # | Milestone | PRD | Done when |
|---|---|---|---|
| 0 | Public-repo config | — | Done. Secrets stay in `.env`. `application.yml` maps every key. |
| 1 | Spring Boot shell | — | Done. `./mvnw test` passes. The app loads `.env` and `/actuator/health` is UP. |
| 2 | Businesses and godowns | F15 | Done. Two businesses, memberships, and godowns migrate on PostgreSQL. A shared owner must select the active business. Queries cannot read the other tenant. |
| 3 | Catalog and parties | F01 | Done. Products keep size, brand, and pack distinct. Approved aliases and box-to-piece conversions are stored. A party can be customer, supplier, or both, without merging lookalike names. |
| 4 | Opening stock | F01 | Done. A confirmed count sets stock once. Repeating the approval does not add quantity again. A recount is an adjustment. Unknown expiry stays unknown. |
| 5 | Posting core | F06 | Done. The office-folder fixture posts stock and paise once: open, receive, sell, take ₹400, return. Replaying webhooks or approvals leaves 62 pieces and ₹400 outstanding. |
| 6 | Purchases | F04 | Done. A supplier bill and a goods receipt keep separate quantities. Stock rises by received quantity. Unknown price can post stock with finance still pending. A later bill does not add stock again. Duplicate bill fingerprints need review. |
| 7 | Sales and receipts | F05, F06 | Done. A sale deducts stock once and refuses to go negative. Invoice amount and money received stay separate. Unallocated receipts stay unapplied. Invoice numbers are not reused. |
| 8 | Supplier balances | F06 | Done. Approved purchase bills raise payables. Receipt of goods alone does not invent a price. Partial supplier payments work. The same party’s receivable and payable are never netted automatically. |
| 9 | Returns and corrections | F07 | Done. Posted rows stay immutable. A return restocks only when restock is approved. Credit is not a cash refund. The correction records reason and approver. |
| 10 | Locations and transfers | F14 | Done. “Agarwal box” has an approved meaning. Godown 3 is remembered without creating a purchase. Receive 1 A and 5 G, sell 1 A and 3 G, transfer 1 G: A is 0, G is 1 and 1. Customer-owned stock is not sellable. |
| 11 | Drafts and approval | F03 | Done. Missing quantity, party, price, unit, or action is not invented. “Yes” approves only one current proposal. Edits invalidate the old approval. Nothing posts before approval. |
| 12 | Dashboard and low stock | F08, F12 | Done. Overview, stock by godown, money, and pending work match the database for the same date. Low-stock alerts use an owner threshold and do not pretend to be forecasts. |
| 13 | Text interpretation | F02 | A Hindi or Hinglish command becomes a validated draft through the Meta client. Tests use a fake client. Instructions inside a bill cannot authorize a tool. |
| 14 | Photos and documents | F02, F04 | A photo or PDF attaches to one open draft and preserves its source. Extraction proposes fields. The owner still approves before any stock or money changes. |
| 15 | Voice receipts | F02, F14 | A voice note is transcribed and becomes one atomic multi-item draft. Unresolved lines are not dropped. “Five boxes” does not become five pieces without a confirmed conversion. |
| 16 | Durable jobs | F10 | PostgreSQL stores due work, leases, and outcomes. `@Scheduled` only wakes the poller. A restart does not claim the same live lease twice. |
| 17 | Reminders and sends | F10 | Owner reminders and customer sends are different tasks. Full payment suppresses the send. A changed balance needs a new approval. Cancellation wins before dispatch. One consenting test send is recorded with its real provider status. |
| 18 | Forecasts | F13 | A 7-day baseline runs only on eligible covered sales. Thin history returns insufficient history. Opening stock, transfers, and purchases are not treated as demand. Reorder suggestions stop when lead time or inbound stock is unknown. |
| 19 | Historical import | F16 | One supported export stages, maps, and reconciles. Activation posts the opening snapshot once. Reimporting the same file changes nothing. History is not replayed into live stock. |
| 20 | WhatsApp ingress | F02 | A signed webhook is stored and acknowledged before any model call. Unknown senders are rejected. Duplicate events do not create a second draft. |
| 21 | Two-tenant demo path | F15 | Receive, locate, sell, correct, and remind run in both businesses. Identically named Agarwal parties do not leak. The demo path matches the recorded expected stock and balances. |

Optional, only after milestone 21 is pushed:

| # | Milestone | PRD | Done when |
|---|---|---|---|
| 22 | Tally connector | F09 | An approved export can be read back from one selected company. A lost response stays unknown until reconciliation. The core app still posts when Tally is down. |
| 23 | E-way connector | F11 | A sandbox submission stores an official identifier only after the provider accepts it. Unknown outcomes are not resubmitted blindly. A local PDF is not labeled as a generated e-way bill. |

Out of these milestones: video shelf counting, GST return filing, bank access, automatic payment collection, payroll, manufacturing, and cross-business transfers.

## Feature notes

- [Public-repo config](doc/features/public-repo-config/AGENTS.md)
- [Spring Boot shell](doc/features/spring-boot-shell/AGENTS.md)
- [Businesses and godowns](doc/features/businesses-and-godowns/AGENTS.md)
- [Catalog and parties](doc/features/catalog-and-parties/AGENTS.md)
- [Opening stock](doc/features/opening-stock/AGENTS.md)
- [Posting core](doc/features/posting-core/AGENTS.md)
- [Purchases](doc/features/purchases/AGENTS.md)
- [Sales and receipts](doc/features/sales-and-receipts/AGENTS.md)
- [Supplier balances](doc/features/supplier-balances/AGENTS.md)
- [Returns and corrections](doc/features/returns-and-corrections/AGENTS.md)
- [Locations and transfers](doc/features/locations-and-transfers/AGENTS.md)
- [Drafts and approval](doc/features/drafts-and-approval/AGENTS.md)
- [Dashboard and low stock](doc/features/dashboard-and-low-stock/AGENTS.md)
