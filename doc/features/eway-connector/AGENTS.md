# E-way connector

Milestone 23. The owner can ask for an e-way bill. The assistant prepares a draft from the posted invoice and the fields the owner stated. A bill number is stored only after the government portal accepts the submission.

## What this covers

- `prepare_eway_bill` is the assistant tool. It creates one draft for a posted sale. Invoice number, date, type `INV`, quantity, and total come from that sale. GSTIN, tax amounts, tax rates, HSN, distance, state, and transport come from the owner. Missing or inconsistent tax stays `VALIDATION_BLOCKED`.
- `submit_approved_eway_bill` is refused when it appears in model JSON. Approval and submit are application actions.
- Approval freezes the payload hash. An edit needs a new approval. A model-supplied bill number or total is ignored.
- The client speaks NIC e-way API v1.03: `POST {base}/ewaybillapi/v1.03/auth` with action `ACCESSTOKEN`, then `POST {base}/ewaybillapi/v1.03/ewayapi` with action `GENEWAYBILL`. The request JSON is Base64 in the `data` field. Headers are `client-id`, `client-secret`, `gstin`, and `authtoken` on the bill call.
- Status `1` with `ewayBillNo`, `ewayBillDate`, and `validUpto` is the only path to `CONFIRMED`. The label then reads `sandbox generated` plus that number and validity. A local copy reads `local copy` until then.
- Status `0` stores the portal error and leaves the invoice unchanged. A corrected draft needs approval before another submit.
- A timeout or a response without those official fields becomes `OUTCOME_UNKNOWN`. The next attempt calls action `GETEWAYBILL` and does not send `GENEWAYBILL` again.
- Cancellation is unsupported. The draft is not deleted, and the owner is told to use the authorized operator.
- Every result is labeled `sandbox` or `production` from the configured environment. The connector stays off until `EWAY_ENABLED` and the credentials are set. No base URL is assumed.

## Spec

`doc/PRD.md` F11, AC48–AC54. `doc/RFC-001.md` section 12 and `prepare_eway_bill` in section 10. Portal reference: [e-way bill API docs](https://docs.ewaybillgst.gov.in/apidocs/), version 1.03. Sandbox host, when credentials exist: `https://ewaybill2.nic.in`.

## How to check

Docker must be running.

```bash
./mvnw -Dtest=EwayClientTest,EwayBookTest test
```

The tests use a scripted portal. They do not create a statutory e-way bill.

## Still open

Live NIC also encrypts the Base64 payload: the auth data with the portal public key, and the bill data with the session key (`sek`). This build records the provider JSON and does not mark a bill generated on its own. Set `EWAY_ENABLED` only after that encryption and real credentials are in place. Tally remains undecided.
