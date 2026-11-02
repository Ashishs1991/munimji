# WhatsApp proposal confirmation

Covers PRD F03 AC10–AC13 and RFC proposal-version approval binding.

A bare Yes binds to the latest successfully sent draft confirmation for the same business, actor and phone, preserving its shown version and content hash. Older drafts and reminders do not block that confirmation. A repeated Yes targets the same posted draft and cannot post an older one. Edited, expired or canceled drafts remain subject to DraftBook approval validation.

V27 adds proposal_shown_at to inbound_messages; existing draft_version/content_hash store the immutable shown snapshot. Only accepted outbound replies participate. No business records or historical draft approvals are changed by migration. Conversations shown before this migration have no snapshot: the bot lists pending entries and accepts `entry <UUID>` to show the selected draft before Yes. No model call is needed for selection.

Run `./mvnw test` (requires Docker for disposable PostgreSQL).

Verification: PostgreSQL-backed WhatsApp tests cover multiple pending drafts, outgoing loan approval, repeated Yes, snapshot edits, and business/actor/phone isolation. Existing retry and reminder tests remain in the suite. No live WhatsApp or Meta call is part of these tests.
