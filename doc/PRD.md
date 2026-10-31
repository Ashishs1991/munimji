# PRD — Vyapar Agent: Digitize My Business

Version: 1.3 · Date: 31 October 2026 · Status: proposed implementation baseline

Project owner: Ashish · Market: Agra MSMEs · Primary demo: brother-in-law’s plastic-bag wholesale business (name to be confirmed)

Working name only; no affiliation with the existing Vyapar accounting product is implied. This document specifies the hackathon product, not completed capabilities. Technical design: [RFC](RFC-001.md).

## 1. Product decision

Build a business personal assistant for Agra MSMEs: inventory and godown management, operational accounting, purchases/sales, customer and supplier balances, scheduled reminders/messages, and demand forecasts, all backed by the application's own verified database and dashboard.

**Promise:** “Tell your assistant what arrived, what sold, where it is kept, and what needs following up. Your business records and dashboard stay together in one system.”

WhatsApp voice/text/photos and the authenticated dashboard are entry points to the same business system. The owner can start from counted stock or import selected history from existing records. Tally is an optional onboarding/integration add-on, not the required database or accounting engine. E-way bills are an optional government connector.

The product is generic across the supported retail/wholesale workflows; it does not claim every industry or arbitrary task is supported. Primary demo business: the brother-in-law's plastic-bag wholesaler. Cure and Care is not the demo and its earlier stock/expiry assumptions do not describe this pilot.

**MVP journey:** choose business → establish/import stock → receive Agarwal/Gupta boxes by voice → locate a box in godown 3 → record sales → update balances → schedule an owner reminder or customer message → show a grounded forecast and dashboard. Demo scope is two isolated business tenants with multiple godowns each. The second real business is to be confirmed; a labeled test tenant can establish isolation until then.

### Revision decisions

- Selected Java + Spring Boot for implementation, reflecting Ashish’s stronger Java experience; product scope and acceptance criteria are unchanged.

- Added location memory, godown transfers, multi-item Hindi voice commands, two tenants, historical imports, demand forecasting, and actual scheduled sends to MVP.
- Moved ongoing Tally sync and e-way bill generation to optional connectors.
- Made the assistant database the post-cutover operational source of truth.
- Retained approvals, provenance, deterministic quantities/money, and recovery from duplicates or uncertain outcomes.

### Implementation baseline

Build one modular Java + Spring Boot application with PostgreSQL/Supabase as the operational database, Spring Data JPA and Flyway for persistence/migrations, and a Thymeleaf + Bootstrap dashboard with modest JavaScript. Call Meta models directly through Spring RestClient; validate structured model proposals before executing approved business operations. Use private object storage for evidence and PostgreSQL-backed jobs for durable reminders and external actions. Scheduled work must survive restarts; an in-memory timer alone is insufficient.

Forecasting uses deterministic Java calculations over eligible history. A Python service, model training, a separate frontend application and an agent framework are not required for MVP. Tally remains an optional connector. Detailed choices, security boundaries and test requirements are in the [RFC](RFC-001.md).

## 2. Problem and evidence

### Known from the owner of this project

- The new primary pilot is a plastic-bag wholesaler in Agra with purchases, sales, and stored goods.
- The owner wants to say “अग्रवाल का बॉक्स गोडाउन नंबर तीन में पड़ा है” and later ask where it is.
- The owner wants multi-item receipts/sales understood from Hindi voice notes and wants reminders to himself or messages to a customer on a specified date.
- Businesses may start with no structured records or bring selected-year history from Tally, registers, or other systems. The wholesaler's actual data quality and export format remain to be inspected.
- Two isolated businesses are requested for the first release. This is two tenants in one deployment, not a combined ledger.
- An earlier Tally connector exists according to Ashish; it has not been inspected. Do not assume Indie-track reuse is permitted: the participant guide explicitly discusses existing codebases for startups. Keep it outside the submitted new-work scope pending organizer clarification.
- The owner is the primary user; assisted onboarding/support remains the intended business model.

### Hypotheses to validate

- Photographing bills/labels and confirming counts by voice takes less effort than manual catalog entry.
- The owner can maintain transaction capture during normal busy periods.
- Reliable stock/location lookup, business balances, reminders, and forecasts are worth paying for.
- Selected history is sufficiently complete for forecast evaluation and a reconciled cutover.
- Existing software plus assisted onboarding does not already solve the owner's needs adequately.

Do not describe these hypotheses as customer-validated outcomes. A purchase bill is evidence of a purchase, not proof of current stock. A camera cannot establish quantities of hidden products. Unknown expiry remains unknown.

## 3. Channel and communication prerequisites

The earlier surgical-demo-specific gate is removed from the demo plan because that is no longer the selected business. Check the actual business/category and intended messaging use during onboarding; do not assume eligibility for every MSME category.

Scheduled sending is an MVP requirement. WhatsApp contact permission and approved templates where required are prerequisites to real delivery. The customer's service window is separate from the owner's conversation window. A reminder to the owner does not authorize sending to the customer. Respect opt-outs and current platform restrictions; do not operate an unrelated debt-collection service. [S1]

Build and test the send path with a consenting test recipient. A blocked template or unavailable channel produces an explicit blocked task and owner notification; it is not a completed send. Do not silently substitute another recipient/channel or label a draft as delivered.

## 4. Users and jobs

| User | Job | Access |
|---|---|---|
| Owner | Establish stock, record activity, approve changes, inspect money owed | One authenticated business; approves all mutations in the MVP |
| Service operator | Set up product units, help resolve unclear records, monitor failed integrations | Explicit business membership; actions attributed to operator |
| Accountant | Review operational records, import reconciliation, and exports | Read-only access if invited; posting permissions separately assigned |
| Judge | Understand the product and inspect evidence | Redacted demo tenant; no production credentials or customer data |

The first release supports **two isolated business tenants**, each with a shop and multiple named godowns/locations, INR, Asia/Kolkata dates, and Hindi/Hinglish/English inputs. Product/party aliases, balances, documents, forecasts, and tasks are business-specific. Shared owners/operators must explicitly select the active business; approvals remain bound to that business after switching. Cross-business transfers and consolidated financial reports are outside MVP.

## 5. Goals and non-goals

### Goals

1. Establish confirmed opening stock without requiring spreadsheet literacy.
2. Turn messages and documents into reviewable transaction drafts.
3. Apply approved changes consistently across stock and receivables.
4. Make corrections possible without erasing history.
5. Remember goods locations, deliver scheduled messages truthfully, and generate evidence-based forecasts.
6. Import selected history with reconciliation and a clear cutover; work without any connector.
7. Show the true status of any enabled optional connector.
8. Measure time saved, correction burden, record accuracy, and task delivery with a real owner.

### Non-goals for this submission

- Automatic complete shelf counting from video; video is a later experiment, not an MVP dependency.
- Invented forecasts from absent/incomplete history. Forecasting is in MVP, with visible insufficient-data behavior and no unsupported seasonality claims.
- General GST return filing, banking access, automatic payment collection, clinical advice, or product substitution advice.
- A universal connector marketplace, arbitrary agent code execution, full payroll, CRM, manufacturing, or cross-business transfers. Same-business godown transfers are in MVP.
- Reconstructing missing historical sales, expiry dates, margins, or opening debts through inference.
- Unapproved financial changes or unrequested outreach. An owner-approved scheduled message executes later without requiring the owner to be online, subject to freshness and channel checks.

## 6. Scope and priority

P0 = required MVP. Optional connector = add-on that does not block the core demo. P2 = later extension.

| ID | Capability | Priority | Boundary |
|---|---|---|---|
| F01 | Business onboarding/opening inventory | P0 | Counted or reconciled imported opening balances |
| F02 | Text, voice, photos and documents | P0 | Multi-item commands; async voice notes |
| F03 | Drafts, clarification, approvals | P0 | Bind effects to tenant and proposal version |
| F04 | Purchases and stock receipts | P0 | Goods can be recorded before pricing is known |
| F05 | Sales, dispatch and invoices | P0 | Stock-only dispatch supported; incomplete revenue clearly pending |
| F06 | Receivables, payables, receipts and payments | P0 | Operational subledgers, not a full statutory general ledger |
| F07 | Returns/corrections | P0 | Linked compensating entries |
| F08 | Complete operational dashboard | P0 | Same business records as the assistant |
| F09 | Tally live import/export bridge | Optional connector | Selected history via export supported by F16; ongoing sync not required |
| F10 | Owner reminders and scheduled customer messages | P0 | Real scheduler and send adapter; consent/template prerequisites |
| F11 | E-way bill generation | Optional government connector | Authorized submission and result verification if enabled |
| F12 | Low-stock alerts | P0 | Owner-set thresholds |
| F13 | Demand forecasts/reorder suggestions | P0 | Numeric method, history quality, evaluation and abstention |
| F14 | Location memory and godown transfers | P0 | Container/lot identity, location history, no accidental stock creation |
| F15 | Two-business tenant management | P0 | Explicit context selection and isolation |
| F16 | One-time historical migration | P0 | Staging, mapping, review, cutover reconciliation; one verified export format first |

No external Tally or government credential is needed to run the core product. Customer messaging credentials/templates are required to demonstrate actual scheduled delivery. Supporting arbitrary source systems automatically is not promised.

## 7. User journeys and acceptance criteria

### F01 — Establish inventory

Owner starts a count session and chooses a shelf/category. Photos identify candidate product names and visible attributes. A voice note supplies counts: “Twelve boxes, each containing one hundred pieces.” The assistant shows the product, brand, size, base unit, pack conversion, and proposed quantity before approval.

- AC01: Product identity preserves size/model/brand distinctions; ambiguous matches ask the owner rather than merging automatically.
- AC02: Counts are confirmed physical observations with an effective date/time and location.
- AC03: Pre-cutover bills/history can inform product identity, costs, and forecasts but cannot add stock again on top of an approved opening snapshot. Import replay and physical opening counts must not overlap.
- AC04: Repeated submission of the same count approval does not increase quantity twice.
- AC05: Recounting produces a reviewed adjustment against current balance, not another opening addition.
- AC06: Expiry/batch is captured when readable and confirmed; otherwise it remains unknown. Historical cost can remain unknown; do not report a stock valuation or margin as complete in that case.
- AC07: Progress shows confirmed items, unresolved items, and the uncounted scope. “Whole shop digitized” requires actual coverage evidence.
- AC08: Count a shelf while transactions for that shelf are paused, or reconcile intervening activity before confirmation. Explain the temporary count procedure to the owner.

### F02/F03 — Message to approved draft

- AC09: A voice note preserves its source and creates a transcript; critical fields appear in a short confirmation.
- AC10: Photos and subsequent messages can attach to a specific open draft. Multiple open drafts require a selection or reply reference.
- AC11: “Yes” approves only an unambiguous, current pending proposal. Changes invalidate older approval buttons/messages.
- AC12: Missing quantity, party role, price, unit, or action is not invented. A stock-only movement can post after its inventory fields are confirmed, while financial completion remains pending.
- AC13: Owner may edit/cancel before approval; no stock, ledger, or external write occurs merely from extraction.
- AC14: Inputs mentioning arbitrary instructions inside a bill cannot authorize tool execution.

### F04 — Purchase and receipt

Supplier bill becomes a purchase draft. The owner identifies whether goods have arrived and how many. Bill and receipt may be recorded together when explicitly confirmed, but retain distinct quantities.

- AC15: Bill number, supplier, dates, lines, totals, and visible tax details retain source references.
- AC16: Amount mismatch or uncertain tax treatment blocks accounting finalization until resolved. An informal bill is not automatically treated as a valid tax invoice or proof of input-tax entitlement.
- AC17: Stock increases by received quantity, not automatically by invoiced quantity.
- AC18: Supplier + bill number + period and document fingerprint detect likely duplicates; possible duplicates require review.
- AC19: Approval commits application records once. Only if an optional Tally export is configured is a job queued; the reply distinguishes local and external status.
- AC20: An offline optional connector cannot block purchases/sales in the assistant. Its pending export is visible and cannot duplicate the local event.

### F05/F06 — Sale, invoice, and customer credit

“Sold two boxes of product A to customer B for ₹1,000 total; ₹400 received.” The assistant confirms the variant, pack unit, total/each interpretation, payment, and effective date.

- AC21: A sale deducts stock exactly once. Insufficient verified stock requests resolution rather than silently going negative.
- AC22: Invoice amount is recorded separately from money received. The example produces a ₹600 receivable after explicit confirmation of the numbers.
- AC23: Customer identification is explicit when names overlap; no balance moves between similarly named parties.
- AC24: Invoice numbering is unique and allocated at posting; canceled numbers are not reused.
- AC25: A tax invoice is finalized only when the business and transaction configuration has been verified. Otherwise label the artifact draft/non-tax as appropriate; never label it “GST compliant” based on model output alone.
- AC26: A later receipt has date, amount, payment method, and invoice allocation. Without allocation it stays unapplied credit; the assistant does not guess which invoice is paid.
- AC27: A payment screenshot or voice statement is owner-reported evidence, not independently bank-verified settlement.

Supplier-side acceptance (IDs appended to preserve earlier references):

- AC79: Approved purchase bills increase supplier payable; goods receipt alone with unknown pricing does not invent a payable. Later bill completion links the receipt without adding stock twice.
- AC80: Supplier payments support partial allocation and unapplied balances. A supplier credit reduces the relevant payable; no payment is executed through a bank by recording it.
- AC81: A party may be both customer and supplier, with separate receivable and payable balances. The assistant cannot automatically net them or treat “Agarwal payment” as an unambiguous direction.
- AC82: Dashboard and chat show the same AR/AP totals, including reconciled openings and subsequent transactions once. Full statutory accounting, payroll, depreciation and tax filing remain outside the MVP accountant role.

### F07 — Return/correction

- AC28: “Two were returned” identifies the original sale, exact item/unit, value, and whether goods are resellable and physically received.
- AC29: A return can increase sellable stock only when restocking is approved. Damaged goods are not silently returned to sellable stock.
- AC30: Credit reduces the amount owed. It does not imply a cash refund; a paid customer can instead have a credit balance awaiting separately recorded refund.
- AC31: Posted records retain their original content. Reversal/replacement entries link to them and show the reason and approver.
- AC32: Corrections after external export remain pending external reconciliation until the target system's corresponding action is verified.

### F08/F12 — Owner visibility

- AC33: Dashboard includes business switcher, stock by godown/container, low stock, applicable unknown expiry, receivables/payables, due/scheduled tasks, forecasts, import reconciliation, approvals, and integration failures.
- AC34: Each number links to supporting records; dashboard and conversational answers agree for the same effective date and scope.
- AC35: “What sold today?” uses transaction occurrence date, not upload time; returns and payments are distinguishable from gross sales.
- AC36: Reports explicitly describe the recorded scope and exclusions. No forecast, profit, or historical trend is fabricated from an empty dataset.
- AC37: Dashboard supports approval, correction request, and investigation of failed actions; status badges distinguish draft, local posted, external pending, and external confirmed.
- AC38: Low-stock notifications use a configured threshold and deduplicate unresolved alerts; they are not described as predictive forecasts.

### F10 — Reminders

- AC39: Support owner reminders and customer-facing sends as separate task kinds. A reminder date is not automatically an invoice due date; overdue labels require confirmed due terms.
- AC40: Customer-facing messages require confirmed recipient, date/year/time/timezone, exact approved message/template parameters, channel eligibility, opt-in, and applicable templates. Approval at scheduling time authorizes execution later if these bindings remain unchanged.
- AC41: Before sending, refresh the linked receivable. Fully paid balances suppress payment messages and notify the owner; changed outstanding amounts require revised approval instead of sending stale amounts. Do not create another debt just because a reminder mentions an existing one.
- AC42: Outside the permitted messaging window, lack of an approved template creates a blocked status, not an unauthorized free-text send.
- AC43: Delivery/read receipts are communication status, never evidence of payment. No automatic repeated collection campaign in the MVP.

### F09 — Optional Tally integration

- AC44: Operator selects a specific company and verified ledger/item/unit mappings before export.
- AC45: Exported purchase can be retrieved from Tally and compared with the approved source totals and line identities.
- AC46: Response loss produces “outcome unknown”; reconciliation precedes any retry.
- AC47: Importing to Tally must not count as another local purchase. Existing unrelated Tally records are outside the local ledger's claimed coverage.

### F11 — Optional government connector: e-way bill

Include an owner-reviewed draft containing required invoice, party, goods, dispatch/delivery, and applicable transport details. Request missing data; do not infer legally consequential fields from product names alone.

- AC48: Draft validates against the chosen provider's current schema and the business's verified configuration.
- AC49: Submission approval binds to the complete draft version; edits require reapproval.
- AC50: Sandbox and production are separate environments and visibly labeled on every result.
- AC51: “Generated” requires an official accepted response and identifier with the returned validity/status information; a locally rendered PDF is not sufficient.
- AC52: Unknown outcome triggers provider lookup/reconciliation; never blindly submit a duplicate request.
- AC53: Rejection preserves actionable errors without changing the invoice silently.
- AC54: Cancellation/update actions, if unavailable, are explicitly unsupported and referred to the authorized operator; no local deletion pretends to cancel an official document.

### F14 — Location memory and multi-item voice stock

Examples are requirements, not actions performed by this document:

- “अग्रवाल का बॉक्स गोडाउन नंबर तीन में पड़ा है।” → identify the box/lot and save a location fact; later “अग्रवाल का बॉक्स कहाँ है?” returns godown 3, last update and relevant quantity if known.
- “एक बॉक्स अग्रवाल का आया है, पाँच बॉक्स गुप्ता के आए हैं।” → after identity/unit/location confirmation, one receipt event has two lines.
- “एक बॉक्स अग्रवाल का बिक गया, तीन बॉक्स गुप्ता के बिक गए।” → deduct 1 and 3 from the confirmed locations. If starting stock was only those receipts, the remaining quantities are 0 and 2 respectively.

- AC55: “Agarwal ka” is clarified initially: product alias/brand, supplier, customer-owned goods, or a labeled box. Save the approved interpretation per tenant; never assume it means a customer debt.
- AC56: A location-only statement creates/updates a searchable location record; it does not create purchased stock, revenue, or a receivable. Customer-owned goods are marked separately from business-owned sellable inventory.
- AC57: Multiple boxes/lots with the same label return a list or clarification. An out-of-stock product may have a historical location but cannot be reported as currently available there.
- AC58: A same-business godown transfer removes and adds the same product/quantity atomically; total business stock and financial balances do not change. Partial transfers leave both locations correct.
- AC59: A multi-item voice command proposes one atomic batch. No line is silently omitted; unresolved identity/quantity prevents that batch posting unless the owner explicitly approves a split.
- AC60: A sale/receipt lacking price can record a confirmed dispatch/receipt with finance pending. Later invoicing links that movement and must not deduct/add stock a second time. “Five boxes” never becomes five pieces without a confirmed conversion.

### F15 — Two business tenants

- AC61: Two businesses have independent products, aliases, locations, documents, balances, forecasts and tasks. Identically named Agarwal/Gupta parties cannot leak or merge across tenants.
- AC62: An owner in both businesses selects an active business visibly. An ambiguous incoming command is clarified; a reply to an old approval remains tied to its original tenant and requires authorized membership.
- AC63: Dashboard switching changes every query/filter; scheduled jobs carry their originating tenant and do not use the owner's later active chat context.

### F16 — Historical migration and cutover

- AC64: Owner selects source company, exact date range/year, cutoff and supported export. Import product/party/unit masters, opening/closing balances, purchase/sale/return records, payments/allocations, and location details only when present. Missing classes are reported.
- AC65: Import into staging, map fields and units, show errors and counts, reconcile stock and party totals, then approve activation. Unresolved rows remain quarantined, not silently discarded.
- AC66: Store source company/record/version IDs or stable file/row fingerprints. Reuploading the same export is idempotent; changed source records create a reviewed difference.
- AC67: Default migration keeps pre-cutover transactions as queryable historical evidence and seeds current balances once from a reconciled snapshot. History is not reposted into current stock/ledger. Physical counts may correct stock at cutover with an explicit adjustment.
- AC68: Post-cutover app entries use the same mapped products/parties. Late source exports are reconciled by source ID, cutoff and owner review, never blindly appended or treated as automatic two-way sync. Imported history schedules no reminders automatically.
- AC69: History and current data display coverage ranges and import status. Forecasts may use verified imported item-level sales, while current financial totals include opening snapshots only once.

### F13 — Demand forecasting in MVP

- AC70: Forecast units by product and a chosen 7-day horizon using recorded/imported item sales, with net returns shown separately. Transfers, opening counts and purchases are not sales demand.
- AC71: Show method/version, data window, coverage, forecast quantity, stock used, and uncertainty/limitations. Missing records are not zero-sale days; known stockout periods are identified as censored demand.
- AC72: Proposed initial eligibility: at least 28 consecutive covered days and 8 positive-sale days for a product; otherwise show “insufficient reliable history” plus a low-stock threshold option. These are conservative product defaults, not proof of forecast accuracy.
- AC73: Evaluate on forward-held-out periods against a simple baseline using MAE and aggregate WAPE where defined. If history or validation is inadequate, label estimates provisional or abstain; do not claim predictive improvement.
- AC74: Reorder suggestions require confirmed supplier lead time, current sellable stock, known inbound stock and commitments. Missing inputs block the recommendation, not the sales forecast. No purchase order is placed automatically.

### F10 — Scheduled message example and standalone debts

“अग्रवाल से पाँच हज़ार रुपये लेने हैं, सत्रह नवंबर को मुझे याद दिलाना” creates an owner reminder. “सत्रह नवंबर को उनको payment reminder भेज देना” creates a customer-send task. Ask whether ₹5,000 is a new unrecorded debt or an existing balance. Ask for the sending time if missing; confirm 17 November 2026 in Asia/Kolkata for this example. The invoice due date remains separate unless the owner specifies it.

- AC75: A genuinely new debt can become an approved standalone receivable, without inventing an invoice or changing stock. An existing matching debt is linked rather than duplicated. Reminder-only notes are explicitly not included in balances.
- AC76: Scheduling captures target kind (owner/customer), confirmed contact, linked debt, amount, date/time/year/timezone, approved payload, and cancellation controls. The owner need not be online when the approved task runs.
- AC77: Durable jobs survive restarts and are claimed once. Retries must avoid duplicate delivery; uncertain provider acceptance triggers reconciliation/manual review. Late jobs beyond a configured grace period require review instead of silently sending days late.
- AC78: Demonstrate one real scheduled delivery to a consenting test contact using a near-term test date. The November 17 example is acceptance data, not a reminder created by this specification. Show provider accepted/delivered/failed distinctly.

## 8. Dashboard information design

The dashboard is the complete operational interface to the same system, with a persistent business selector:

1. **Overview:** recorded sales/receipts, receivables/payables, pending work and data coverage.
2. **Stock & godowns:** products, variants, packaging units, containers/ownership, quantities by location, last-seen evidence, transfers and history.
3. **Transactions & money:** purchase/sale documents, stock-only receipts/dispatches awaiting finance, invoices, customer/supplier balances, payments and credits.
4. **Tasks & messages:** owner reminders, customer sends, calendar/list, scheduled time, approval, cancellation, delivered/failed/blocked state.
5. **Forecasts:** eligible product forecasts, history quality, hold-out metrics and grounded reorder suggestions.
6. **Import & review:** staged history, mapping/reconciliation, approvals, and optional connector status.

Support keyboard navigation, readable contrast, clear empty/error/loading states and private evidence links. This is an owner workspace, not decorative reporting. Do not make chat and dashboard separate databases.

## 9. Evaluation and release targets

These are proposed acceptance targets, not measured results or statistical guarantees.

| Measure | Target / method |
|---|---|
| Product bootstrap | 20–30 physically verified variants; include confusing names and units |
| Document set | 10–15 real permissioned/redacted bills if available; report actual sample size |
| Held-out evaluation | Reserve at least 5 variants and 3 bills from prompt tuning when sample permits |
| Draft quality | Report field-level accuracy before correction, and clarification frequency; do not hide failures behind owner edits |
| Final record correctness | All posted quantities and money in the release test set match independently prepared ground truth |
| Opening-stock effort | Aim for at least 30% less elapsed time than manual entry, including corrections; failure means investigate value proposition |
| Mutation reliability | Zero duplicate financial or stock effects in repeated-delivery and retry tests |
| Authorization | No cross-business reads/mutations; unauthorized sender cannot approve |
| Latency | Targets: webhook persistence/ack under 2 seconds; text draft p95 under 10 seconds, photo/voice under 30 seconds on tested samples |
| Owner usability | Owner completes opening stock, purchase, sale, and correction without dictated prompts; record assistance minutes |
| Integration truthfulness | Every displayed confirmed external action has a verifiable external reference |
| Location/voice | Agarwal/Gupta two-line receipt/sale, partial transfer and sold-out lookup match independent expected state |
| Migration | Reimport is idempotent; accepted opening balances and source totals reconcile without double counting |
| Forecast | Report covered history, forward-held-out metrics and abstentions; no invented performance |
| Scheduled sends | One actual consenting-contact delivery, restart test, cancellation, partial/full payment freshness tests |
| Tenant isolation | Complete the same named-party workflow independently in both tenants |

Compare the same inputs against manual entry and ordinary AI chat. Rotate order or use comparable examples to reduce practice effects. Record device, audio noise, missing fields, model/version, timings, intervention, and final result. A small pilot supports an observed demonstration, not a market-wide claim.

## 10. Hackathon story and evidence

Required three-minute demo video: the participant guide confirms this duration. Suggested sequence:

- 0:00–0:20: Introduce the Agra wholesaler and the owner's actual stock/location problem.
- 0:20–0:55: Show a reviewed opening count or historical import; speak the two-line Agarwal/Gupta receipt.
- 0:55–1:20: Save “godown 3,” retrieve it, then record a multi-line sale and show correct remaining stock.
- 1:20–1:50: Record/link ₹5,000 owed and schedule a customer reminder to a consenting test contact; demonstrate actual near-term delivery.
- 1:50–2:20: Dashboard and data-backed forecast with clear history/quality labels.
- 2:20–2:40: Switch business and show isolation; optional connector only if already proven.
- 2:40–3:00: Actual user effort/accuracy evidence and honest limitations.

Show the Meta model's actual interpretation and tool decisions through concise structured traces, not hidden chain-of-thought or a theatrical animation. Do not claim automatic shelf counting if counts were supplied by a human.

The participant guide confirms impact 30%, Meta tooling 25%, demo 25%, and technical depth/execution 20%. Stage one checks theme fit and meaningful use of Meta Model API and/or Meta open-weight models; top five per track advance to the final stage. [S6]

**Confirmed submission obligations:** public repository, accessible three-minute demo video, one submission per team, list everyone who worked on the project, and clearly identify event-built features/code/functionality. The guide warns of immediate disqualification for unclear original contributions and names generic/basic RAG and simple image-analyzer projects among anti-project examples. Show the operational workflow, not only extraction or chat. The video, code and live product are judging artifacts; provide practical judge access without exposing production data.

**Confirmed bonus:** Best Agentic Use-Case is $5,000, judged on autonomy, task effectiveness and usefulness. One well-designed agent with tools can demonstrate the stated criteria; the guide does not require multiple agents. Do not remove justified approvals to chase autonomy. Best Build on Meta Open Weight Models is $10,000 and specifically uses Muse Glimmer or Muse Spark Open with verification; hosted Muse Spark use alone does not establish eligibility for it.

Maintain a dated contribution record and separate pre-event assets. The guide explicitly permits startups to build on existing products/codebases; Indie reuse of the old connector needs clarification. It does not explicitly address changing the application idea or mixing other providers. See [rules review](HACKATHON-RULES-REVIEW.md).

## 11. Delivery plan and gates

| Date, IST | Work and exit condition |
|---|---|
| Oct 31 | Confirm wholesaler vocabulary, godowns and source exports; two-tenant schema; channel/model access; template/recipient setup; count/import sample |
| Nov 1 | Opening balances, location memory and atomic voice receipts/sales; dashboard; historical import staging and reconciliation |
| Nov 2 | Receivables/payables, invoices, scheduled owner/customer tasks, forecasting baseline and quality checks |
| Nov 3 | Owner trials, forward-held-out forecasts, two-tenant/failure tests; optional connectors only after core reliability |
| Nov 4 | Feature freeze, regression, demo and submission; finish in Indian evening |

Published submission deadline: 4 November 2026 at 9 PM Eastern = 5 November at 7:30 AM IST. Recheck organizer changes. [S3]

This revision increases the core scope. Prioritize voice inventory/location, system-of-record correctness, migration, scheduled delivery and forecasting before optional connectors. A blocked messaging prerequisite remains an MVP limitation, not a reason to count drafts as sent. Report unfinished core features honestly.

## 12. Business pilot

Offer guided setup plus ongoing supported operation to a narrow group of similar shops. Price is to be discovered, not invented in this PRD. Measure setup hours, support minutes per week, infrastructure usage, record corrections, recurring use, and willingness to pay. Universal customization and unlimited manual support are not promised.

## 13. Risks and unresolved decisions

| Risk/question | Resolution owner | Required point |
|---|---|---|
| Actual business/channel and reminder use eligibility | Ashish/provider | Before affected live messaging |
| Exact meaning of Agarwal/Gupta aliases, box units and ownership | Owner | Before catalog/location onboarding |
| Second pilot business and authorized memberships | Ashish | Before second live tenant; use labeled test tenant meanwhile |
| Source export format, selected year, cutoff and historical completeness | Owner/operator | Before import activation |
| Model access and Hindi/Hinglish accuracy | Builder | Oct 31 spike |
| Customer contacts, opt-in and approved template | Owner/builder | Before scheduled live-send acceptance |
| Correct business/tax/invoice settings | Owner/accountant | Before final invoices |
| Forecast history, lead times and validation | Builder/owner | Before predictive/reorder claims |
| Optional Tally/e-way credentials and compatibility | Operator | Only before enabling the relevant add-on |
| Real data permission and owner capture habits | Owner/pilot | Before ingestion/publication; measure during trial |

## 14. Definition of done

- P0 acceptance criteria pass on the recorded test set.
- Optional connector status is recorded separately; no mocked success presented as live.
- Location/transfer, import/cutover, forecasting, two-tenant isolation, supplier subledger and actual scheduled-message tests pass. Any failure remains an explicitly open MVP item and prevents calling the full MVP complete.
- At least one complete owner workflow is demonstrated using an actual input channel.
- Evidence supports final quantities, balances, and claimed integrations.
- Public repository, accessible three-minute demo, judge-accessible product, setup instructions, evaluation evidence and clear event-built contribution record are ready; all contributors are listed in the single submission.
- No production secrets, raw private customer documents, or misleading claims are published.

## Sources and verification boundary

Revised 31 October 2026. Messaging policy rechecked on this date; other provider/event references were reviewed on 30 October and must be checked at implementation. Product requirements are design decisions, not claims that providers implement them.

- [S1 — WhatsApp Business Messaging Policy](https://whatsappbusiness.com/policy/)
- [S2 — Hackathon official terms](https://cerebralvalley.ai/e/meta-ai-developer-hackathon/waiver)
- [S3 — Meta event schedule](https://dev.meta.ai/events/global-hackathon)
- [S4 — E-way bill API and release notes](https://docs.ewaybillgst.gov.in/apidocs/)
- [S5 — Tally e-way bill FAQ](https://help.tallysolutions.com/faq-e-way-bill-tally/)

No user account, existing source code, live Tally instance, or e-way bill credential was inspected while writing this PRD.

- [S6 — Participant guide](https://cerebralvalley.ai/e/meta-ai-developer-hackathon/details), reviewed from user-supplied text on 31 October 2026; the live page required login.
