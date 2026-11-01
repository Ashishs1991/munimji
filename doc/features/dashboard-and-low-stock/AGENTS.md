# Dashboard and low stock

Milestone 12. The owner page reads the same stock, money, and pending work as the books for the date on the page. A low-stock alert is an owner threshold, not a forecast.

## What this covers

- For one date, stock by godown, receivables, payables, sales, returns, and receipts match the posted rows whose business time falls on that date. A later sale does not change the earlier date.
- Unknown expiry is listed when stock has no batch expiry. Customer-owned stock is left out of the on-hand table.
- Pending work counts proposals that still need a field, proposals waiting for approval, and posted rows whose finance is still pending. Owner reminders that are waiting, scheduled, or shown on the dashboard are listed on the page.
- Cookie changes to the page require the signed-in user to be an owner of that business and to send the dashboard CSRF token. A bearer token still identifies the owner for API calls.
- Each sales figure links to the document id behind it. Payables are not netted with receivables.
- An owner in two businesses sees no figures until one business is selected. The other business’s stock does not appear.
- A low-stock alert opens when on-hand stock for that date is at or under the owner’s minimum. Opening the page again does not create a second alert. Raising the minimum above the stock resolves it. The page says the alert is not a forecast, and it does not invent a forecast or a profit.

## Spec

`doc/PRD.md` AC33–AC38, for the records that exist now. `doc/RFC-001.md` section 6 on dashboard queries staying inside the selected business. Forecasts, import reconciliation, and integration failures are later milestones, so those sections say they are not available.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=DashboardTest test
```

The page is `GET /dashboard?userId=&date=`.

## Still open

Forecasts and import status are not on this page. A historical date still refreshes low-stock alerts.
