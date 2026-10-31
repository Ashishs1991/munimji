create table whatsapp_senders (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    user_id uuid not null,
    phone text not null unique
);

create table inbound_messages (
    id uuid primary key,
    business_id uuid references businesses (id),
    user_id uuid,
    message_id text not null unique,
    phone text,
    body text,
    status text not null,
    draft_id uuid,
    constraint inbound_messages_status check (status in ('STORED', 'REJECTED', 'STATUS_CALLBACK'))
);
