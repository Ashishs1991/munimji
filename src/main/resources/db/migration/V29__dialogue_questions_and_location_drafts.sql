create table dialogue_state (
    business_id uuid not null references businesses(id),
    actor_id uuid not null,
    draft_id uuid not null references drafts(id),
    draft_version bigint not null,
    question text check (question in ('CREATE_PARTY', 'CREATE_PRODUCT', 'PRODUCT_UNIT', 'PARTY_NAME', 'PRODUCT_NAME', 'MATCH_PARTY', 'STOCK_ACTION', 'USE_PARTY')),
    proposed_name text,
    proposed_unit text,
    expires_at timestamptz not null,
    primary key (business_id, actor_id)
);
alter table dialogue_state enable row level security;
alter table drafts add column location_label text;
alter table drafts drop constraint drafts_kind;
alter table drafts add constraint drafts_kind check (
    kind is null or kind in ('SALE', 'RECEIPT', 'PAYMENT', 'RECEIVABLE', 'PAYABLE', 'OUTGOING_PAYMENT', 'LOCATION')
);

alter table inbound_messages add column history_saved boolean not null default false;

create table interpretation_turns (
    id uuid primary key,
    business_id uuid not null references businesses(id),
    actor_id uuid not null,
    source_text text not null,
    model_reply text,
    draft_id uuid references drafts(id),
    resolved_kind text,
    created_at timestamptz not null
);
create index interpretation_turns_business_time on interpretation_turns (business_id, created_at desc);
alter table interpretation_turns enable row level security;

alter table inbound_messages add column reply_sent_at timestamptz;

alter table inbound_messages add column reply_text text;
create index inbound_messages_latest_reply
    on inbound_messages (business_id, user_id, phone, reply_sent_at desc)
    where reply_sent_at is not null and status = 'REPLIED';
