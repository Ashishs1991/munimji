# Purchase clarification recovery

Covers PRD F02/F03 AC09–AC13 and stock-only receipt F04; RFC interpretation, tenant resolution and approved posting.

The interpreter receives a tenant-scoped product catalog (including approved unit labels), godown codes/names, and full open-draft fields. V28 preserves heard party/product/location names separately from resolved IDs. Hindi/English numbered godown labels resolve only to an unambiguous registered location. Explicit target_draft_id is validated against current-business open drafts before any catalog mutation. Ambiguous clarification asks for a draft instead of creating another empty form.

Unknown supplier/product questions for single-item receipts retain known quantity/unit/godown and ask one question at a time. Product registration requires explicit create_product and a stated base unit; unknown mentions alone do not create records. Supplier creation during receipt follow-up uses SUPPLIER role. Inventory still posts only after owner approval, and unknown price does not invent a payable.

Run ./mvnw test with Docker for isolated PostgreSQL. Regression tests cover the 1,000-bori Hindi voice receipt, context, explicit party/product creation, ambiguous follow-ups, cross-tenant targets, and stock posting with unknown price.

Limits: prompt context is capped at 100 parties/products; larger shops need retrieval. Cross-script party/product identity relies on canonical model extraction or existing approved product aliases; no automatic fuzzy identity merge. New products use the explicitly stated unit as their indivisible base unit; conversions and divisible base-unit setup remain catalog workflows. Multi-item receipts retain their existing clarification path. No live database or API is modified/called in tests. Migration runs on application restart; existing draft rows receive null heard-name fields and are not rewritten.

Validation maintenance: the e-way response test now checks the ewayBillNo JSON field instead of rejecting the substring 999 anywhere in the response (random UUIDs could contain it). No e-way production behavior changed.
