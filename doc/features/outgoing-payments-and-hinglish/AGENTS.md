# Outgoing payments and Hinglish replies

Updated 2 November 2026. Fixes the distinction between “maine Anisha ko ₹5000 diye” (paid out) and “Anisha ko ₹5000 dene hain” (still owed).

## Behaviour

- `outgoing_payment` is a separate approved draft kind. It does not create a supplier payable just because money was paid.
- The owner confirms whether the payment settled an existing bill/balance (`bill_payment`), was an advance (`advance`), or was lending (`loan`). Party, amount and payment method are retained while purpose is clarified. Only one question is asked at a time for this action.
- Settling a bill reduces a recorded supplier balance; it refuses amounts exceeding that balance. An explicit advance records supplier credit, not new debt. A confirmed loan records a receivable. Stock does not change.
- On approval, a party's financial role can expand to BOTH to retain its existing role while enabling the explicitly approved loan/supplier operation. No role change happens merely during extraction.
- Correcting an unposted money proposal with an explicit outgoing-payment edit preserves the same party's known amount when the target is unambiguous and suspends the old proposal. Posted entries are not silently rewritten or reversed.
- A bare amount answering a money question stays with that action; a standalone bare amount asks whether money came in, went out, or is owed. It does not create an empty stock form.
- The model is instructed to reply in Roman Hindi/Hinglish. Fixed WhatsApp interface phrases are rendered deterministically in Hinglish; names and rupee values remain intact. New outgoing-payment confirmations explicitly say “aapne ... ko ... diye”.
- Apply `V26__outgoing_payment_drafts.sql` through Flyway before testing the deployed build.

## Verification

Run `./mvnw test`. Tests cover paid versus owed, correcting a pending payable without losing ₹5000, explicit advance posting and replay, supplier bill settlement, loans, stock invariance, an amount-only reply, and Hinglish confirmation wording.

Live model classification has not been verified in this change. Automatic approval review blocked sending the repository's system prompt to Meta using the local key; no such request was sent. Test this with the owner's authorization or through the deployed WhatsApp rehearsal.

## Rehearsal

Say “maine Anisha ko panch hazaar diye”. The expected clarification is:

“Samjha: aapne Anisha Gajak ko ₹5000.00 diye. Purane bill ka bakaya chukaya, advance diya, ya udhaar diya?”

After the purpose is answered, ask only for a missing payment method. Confirmation must describe the outgoing payment and its correct ledger effect. If it was a bill payment but no payable was recorded, do not manufacture a matching debt merely to make posting succeed. Earlier wrongly approved entries need an explicit correction after reviewing their source.
