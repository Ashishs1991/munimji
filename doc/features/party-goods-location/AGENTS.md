# Party goods location lookup

PRD F02/F14: read queries must not create location notes, and inventory locations must be grounded in posted records.

The observed Hindi question "निर्मल ट्रेडर्स का माल कौन से गोदाम में पड़ा है" was correctly classified by Meta as locate_goods/read_query, then overwritten by the source storage heuristic. Read intents and Hindi/Hinglish question words now prevent that write override. Clear where-stored questions remain reads even when a storage verb is present.

A party-only location query resolves the tenant's saved party in any role, finds product IDs through posted PURCHASE documents/lines, and reports current BUSINESS stock by godown, aggregated across batches. Unposted drafts are not stock. Transfers are reflected from current balances, not stale receipt locations. When no positive stock remains, purchase-record locations are explicitly historical, not availability. The response describes total current stock for products received from the supplier; it does not claim supplier-lot attribution when multiple suppliers share a SKU. Existing exact label notes and product lookups remain supported.

No schema migration or historical data correction is needed. The accidentally created unposted location note is left for owner review rather than silently deleted. Run ./mvnw test with Docker: tests cover the actual Hindi phrase, a competing pending receipt, no-posted-stock handling, transfers and business isolation. Real Meta tests remain opt-in.
