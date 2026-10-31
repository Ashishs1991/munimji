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

| # | Feature | Done when |
|---|---|---|
| 0 | Public-repo safety and single config | `.env` ignored; every key mapped in `application.yml`; this file exists |
| 1 | Spring Boot shell | App starts, loads `.env`, health check passes, Maven Wrapper committed |
| 2 | Tenant schema | Business, membership, and godown tables migrate on PostgreSQL |
| 3 | Posting core | Office-folder fixture posts stock and paise once; replay does not double-count |
| 4 | Drafts and approval | Invalid or stale approval cannot post; posted rows stay immutable |
| 5 | Locations | Agarwal/Gupta receipt, godown lookup, and partial transfer match the RFC fixture |
| 6 | Dashboard | Stock, balances, and approvals render from the same database |
| 7 | Meta text interpretation | A Hindi command becomes a validated draft; tests use a fake client |
| 8 | Voice receipts | A voice note is transcribed and becomes one atomic multi-item draft |
| 9 | Reminders | Durable job survives restart; paid or changed balances do not send a stale message |
| 10 | Forecasts | Eligible history returns a labeled baseline; thin history abstains |
| 11 | Historical import | Reimport is idempotent; opening snapshot is not replayed into live stock |
| 12 | WhatsApp ingress | Signed webhook is stored before any model call |
| 13 | Two-tenant demo | The same party names stay isolated, and the demo path runs end to end |

Tally and e-way bills stay optional and start only after milestone 13.

## Feature notes

- [Public-repo config](doc/features/public-repo-config/AGENTS.md)
