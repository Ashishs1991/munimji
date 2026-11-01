alter table inbound_messages add column reply_status text;
alter table inbound_messages add column posted_document_id uuid;
alter table inbound_messages add column media_id text;
alter table inbound_messages add column media_type text;
alter table inbound_messages add column approval_bound boolean not null default false;
