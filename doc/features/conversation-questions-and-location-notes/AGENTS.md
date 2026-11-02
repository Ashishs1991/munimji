# Conversation questions and location notes

Covers PRD F02/F03 AC09–AC14, F04 stock-only receipts and F14 AC55–AC57; RFC owner approval, tenant boundaries and idempotent posting.

## Behaviour

- V29 adds per-business/actor durable dialogue focus and typed clarification questions, tied to draft version and a two-hour expiry. A contextual Haan can approve an offered party/product or a role change; it cannot simultaneously post stock/money. A separate current proposal approval is required.
- Repeating an unknown product name produces a concrete creation offer using the already stated base unit. A conflicting Haan/Nahi transcript asks for clarification. Negative answers, expiry and stale versions cannot create entities. Existing party identity and newly required customer/supplier role are confirmed separately; roles are preserved as BOTH on explicit consent.
- remember_location produces a LOCATION draft with a label and godown. Approval calls the existing LocationBook and records unknown ownership, without documents, purchased stock or ledger entries. Labels can be searched using locate_goods. The Hindi/English storage-source guard prevents the observed goods_received misclassification when a user merely states where goods are kept. Unknown or action-less quantity statements ask which action was intended.
- Short answers use the focused draft instead of searching all old forms. Selecting entry <UUID> also reconstructs the relevant onboarding question for a pre-V29 pending draft. Unresolved corrections clear the old resolved identity instead of silently retaining it.
- Voice confirmations use the same approval binding as text, after transcription. The latest successfully sent reply controls approval; a read query or other topic cannot silently approve an older proposal. Legacy unbound single-proposal tests remain supported. Reminder proposal replies remain bindable.
- Approval/selection responses are recorded in actor-scoped conversation history. history_saved prevents retries from duplicating history. Cached reply_text makes provider retries resend the exact prepared response rather than reinterpreting a previous Yes. interpretation_turns records source text, raw model response (not system prompt or API key), final draft ID/kind and time for future read-only diagnosis.
- Bill extraction cannot authorize party/product creation; extraction flags are stripped.

## Verification

./mvnw test uses disposable PostgreSQL and fake network providers. Regression cases include actual Hindi storage text, new-party loan, repeated product names, two-stage voice approval, role confirmation, ambiguous action, negative/conflicting consent, topic changes, stale draft versions, tenant/actor expiry boundaries, document instructions, and raw-vs-resolved trace evidence. No running business DB is used.

The opt-in LiveConversationEvaluationTest exercises the real Meta client on synthetic Hindi loan/location/purchase conversations in disposable PostgreSQL. Run python3 scripts/check-live-conversations.py with Docker running. It reads only META_API_KEY from local config for authentication, disables WhatsApp auto-reply, and overrides all datasource settings with the test container. The user chose to run this themselves; the agent has not called Meta.

## Limits and migration

V29 adds schema/state only and does not repair or replay historical transactions. Existing mistaken drafts require explicit review; posted records remain immutable. Cross-script entity identity and unrestricted speech still depend on the model/ASR; clear proposals and confirmation remain necessary. Existing 100-product/party prompt limits and indivisible new-product base units remain. Multi-item entity onboarding, unknown pack conversions, and stock transfers retain their existing separate workflows; this change does not claim universal language understanding or forecasting improvements.
