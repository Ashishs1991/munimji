alter table drafts drop constraint drafts_status;
alter table drafts add constraint drafts_status check (
    status in ('NEEDS_CLARIFICATION', 'AWAITING_APPROVAL', 'POSTED', 'SUSPENDED', 'CANCELED')
);

alter table drafts drop constraint drafts_kind;
alter table drafts add constraint drafts_kind check (
    kind is null or kind in ('SALE', 'RECEIPT', 'PAYMENT', 'RECEIVABLE', 'PAYABLE')
);

alter table drafts add column payment_method text;
alter table drafts add column allocates_document_id uuid;
alter table drafts add column cause text;
alter table drafts add column direction text;

alter table documents drop constraint documents_kind;
alter table documents add constraint documents_kind check (
    kind in (
        'PURCHASE',
        'SALE',
        'RECEIPT',
        'CREDIT',
        'SUPPLIER_BILL',
        'SUPPLIER_PAYMENT',
        'REFUND',
        'OPENING',
        'LOAN',
        'PAYABLE_NOTE'
    )
);

alter table party_ledger_entries drop constraint party_ledger_reason;
alter table party_ledger_entries add constraint party_ledger_reason check (
    reason in ('SALE', 'RECEIPT', 'CREDIT', 'PURCHASE', 'PAYMENT', 'REFUND', 'OPENING', 'LOAN', 'OBLIGATION')
);

create table conversation_turns (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    actor_id uuid not null,
    role text not null,
    body text not null,
    created_at timestamptz not null,
    constraint conversation_turns_role check (role in ('owner', 'assistant'))
);

create index conversation_turns_business_created on conversation_turns (business_id, created_at desc);
