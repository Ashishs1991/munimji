# Munim Saathi

A Hindi-first business assistant for Agra MSMEs, starting with a wholesale business: inventory, godown locations, purchases and sales, operational ledgers, scheduled reminders, and data-backed forecasts.

Working project name. Planning documents are specifications, not implemented features.

## Start here

- [Product requirements](doc/PRD.md)
- [Technical RFC](doc/RFC-001.md)
- [Hackathon rules review](doc/HACKATHON-RULES-REVIEW.md)

Open this folder in Cursor and read the PRD and RFC before implementing. Begin with one verified receive → locate → sell → correct → remind workflow. The agreed MVP supports two isolated businesses. Tally and e-way bill integrations are optional add-ons.

The documents propose a Python/FastAPI backend, PostgreSQL/Supabase, and a practical dashboard. No application code or dependencies have been scaffolded yet.

## Hackathon contribution record

Keep dated commits and clearly identify work built during the event. The old Tally connector is pre-event work and requires eligibility clarification before Indie-track reuse. See the rules review.

## Private data

Keep API keys, credentials, raw customer records, and private business documents out of Git. Use redacted or synthetic fixtures. Public repository access is required for submission; this repository is currently local only.
