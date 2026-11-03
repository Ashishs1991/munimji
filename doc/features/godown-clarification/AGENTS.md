# Godown clarification

PRD F02/F03, stock receipts and location memory. V30 adds CREATE_GODOWN/GODOWN_NAME questions without changing stock or ledger records.

A godown-shaped location_label is retained as location_code when the model misplaced it. Short numbered godown answers are handled directly against the focused missing-location field; a bare number is a location only when that field is unambiguous. Missing locations get an explicit creation offer, not another generic location question. Haan creates/reuses the tenant-local godown and continues the same draft; posting stock requires another approval. Nahi asks for an existing godown. Numbered locations use canonical g<number> codes; conflicts require clarification and are not silently duplicated.

Regression tests replay Godown 3 mai, repeated 3, consent, decline and an existing numbered location, plus the exact observed model response shape. ./mvnw test uses disposable PostgreSQL; live Meta evaluation remains opt-in and is not called by the agent. Real records are not repaired or replayed by migration.
