alter table drafts add column document_fields text;
alter table drafts add column document_date date;
alter table inbound_messages add column reply_to_message_id text;
alter table inbound_messages add column outbound_message_id text;
create index inbound_messages_outbound_reference on inbound_messages (business_id, user_id, phone, outbound_message_id)
    where outbound_message_id is not null;

alter table inbound_messages add column processing_token uuid;
alter table inbound_messages add column processing_until timestamptz;
