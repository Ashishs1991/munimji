# Durable jobs

Milestone 16. Due work, leases, and outcomes live in PostgreSQL. The scheduler only wakes the poller.

## What this covers

- A job stores its business, kind, payload, due time, lease, attempt count, and outcome.
- The poller claims one due job with `FOR UPDATE SKIP LOCKED`. A second worker, including a restart, cannot take that job while the lease is still live.
- An expired lease can be claimed again. The attempt count increases.
- A `PING` job records the outcome `recorded` and does not send a message. Waking the poller again does not record it a second time.
- `@Scheduled` calls the poller and does no other work.

## Spec

`doc/PRD.md` AC77’s claim-once rule. `doc/RFC-001.md` sections 5 and 14: PostgreSQL leases, and `@Scheduled` only wakes the poller. Owner reminders and customer sends stay open.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=JobLeaseTest test
```

## Still open

Nothing is sent from a job. Reminder dates, consent, and provider status belong to the next milestone.
