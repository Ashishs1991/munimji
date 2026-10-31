# Munim Saathi

A Hindi-first business assistant for Agra MSMEs, starting with a wholesale business: inventory, godown locations, purchases and sales, operational ledgers, scheduled reminders, and data-backed forecasts.

Working project name. Planning documents are specifications, not implemented features.

## Start here

- [Product requirements](doc/PRD.md)
- [Technical RFC](doc/RFC-001.md)
- [Hackathon rules review](doc/HACKATHON-RULES-REVIEW.md)

Open this folder in Cursor and read the PRD and RFC before implementing. Begin with one verified receive → locate → sell → correct → remind workflow. The agreed MVP supports two isolated businesses. Tally and e-way bill integrations are optional add-ons.

The application is Java and Spring Boot, with PostgreSQL/Supabase and a Thymeleaf dashboard still to come. Settings and API keys are mapped in `src/main/resources/application.yml` and supplied from a local `.env` file. Copy `.env.example` to `.env` and fill in values there. Run `./mvnw test` with Docker running. The app has a health check, businesses, godowns, a catalog, parties, stock, purchases, sales, receipts, supplier balances, returns, godown transfers, and draft approval. The next milestone is the dashboard and low stock.

## Hackathon contribution record

Keep dated commits and clearly identify work built during the event. The old Tally connector is pre-event work and requires eligibility clarification before Indie-track reuse. See the rules review.

## Private data

Keep API keys, credentials, raw customer records, and private business documents out of Git. Use redacted or synthetic fixtures. Put secrets in `.env` only. Public repository access is required for submission.
