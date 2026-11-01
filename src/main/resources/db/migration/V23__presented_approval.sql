alter table drafts add column presented_version bigint;
alter table drafts add column presented_hash text;

alter table inbound_messages add column reminder_task_id uuid;
alter table inbound_messages add column draft_version bigint;
alter table inbound_messages add column content_hash text;
