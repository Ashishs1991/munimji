# Businesses and godowns

Milestone 2. Two businesses can exist in one database. A person who belongs to both must choose the active business before godown queries run. Those queries only return the chosen business.

## What this covers

- Flyway migration `V1__businesses_and_godowns.sql` creates `businesses`, `memberships`, `locations`, and `business_contexts`.
- A membership is unique per person and business. A godown code is unique inside one business and may repeat in another.
- One membership is used automatically. Two memberships with no selection raise `ActiveBusinessRequiredException`.
- Selecting a business the person does not belong to is rejected and does not write a context row.
- Hibernate validates the schema. Flyway owns changes. Tests use PostgreSQL in Testcontainers, not an in-memory database.

## Spec

`doc/PRD.md` F15 and AC61. `doc/RFC-001.md` businesses, memberships, and locations in section 6, and multi-business identity in the same section.

## How to check

Docker must be running.

```bash
./mvnw test
```

`TenantIsolationTest` covers single-business listing, required selection, cross-tenant hiding, rejected selection, and the uniqueness constraints.

## Still open

Products, aliases, parties, and stock are later milestones. Supabase login is not wired yet; tests pass an application user id. There is no dashboard switcher yet.
