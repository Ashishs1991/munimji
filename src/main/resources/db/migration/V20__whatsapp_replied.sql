alter table inbound_messages drop constraint inbound_messages_status;

alter table inbound_messages add constraint inbound_messages_status check (
    status in ('STORED', 'REJECTED', 'STATUS_CALLBACK', 'REPLIED')
);
