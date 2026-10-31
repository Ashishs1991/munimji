alter table documents drop constraint documents_kind;

alter table documents add constraint documents_kind check (
    kind in (
        'PURCHASE',
        'SALE',
        'RECEIPT',
        'CREDIT',
        'SUPPLIER_BILL',
        'SUPPLIER_PAYMENT',
        'REFUND'
    )
);

alter table party_ledger_entries drop constraint party_ledger_reason;

alter table party_ledger_entries add constraint party_ledger_reason check (
    reason in ('SALE', 'RECEIPT', 'CREDIT', 'PURCHASE', 'PAYMENT', 'REFUND')
);

create table corrections (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    operation_id uuid not null unique references posted_operations (operation_id),
    source_document_id uuid not null references documents (id),
    replacement_document_id uuid not null references documents (id),
    reason text not null,
    approver_id uuid not null
);
