# WhatsApp media download reliability

Media failures happen before ASR or intent interpretation. The client previously swallowed all exceptions and re-encoded already-signed CDN URLs via the URI-template overload.

Use URI.create for the returned signed URL so percent escapes remain intact. Retry temporary 429/5xx/transport/empty-content failures once, acquiring a fresh media URL. Do not blindly retry authorization/permission failures. Log lookup/content stage, status, provider error code/trace or exception type; never authorization headers, signed URLs or raw response bodies. Voice-download failure explicitly offers text continuation and preserves the pending draft.

Tests use mock HTTP and disposable PostgreSQL: signed URL escapes, fresh-lookup retry, no auth retry, and failed voice download followed by Haan bana do on the same draft. Run ./mvnw test with Docker. No schema migration is needed.

Read-only investigation found both reported failed audio files and the successful control downloadable with local credentials at inspection time. That does not prove which exception occurred on the deployed worker: the old client discarded it. The URL-encoding bug was reproduced in a local test. Rehearse a new voice note after deployment; if failure continues, inspect WhatsApp media failed stage=... logs and compare deployed credentials/network configuration. No voice inference or business-record write was made by the diagnostic.
