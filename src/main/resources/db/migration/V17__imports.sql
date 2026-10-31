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
        'OPENING'
    )
);

alter table party_ledger_entries drop constraint party_ledger_reason;

alter table party_ledger_entries add constraint party_ledger_reason check (
    reason in ('SALE', 'RECEIPT', 'CREDIT', 'PURCHASE', 'PAYMENT', 'REFUND', 'OPENING')
);

create table imports (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    file_name text not null,
    file_hash text not null,
    status text not null,
    opening_pieces numeric(18, 6) not null,
    opening_receivable_paise bigint not null,
    historical_sales integer not null,
    quarantined_rows integer not null,
    unique (business_id, file_hash),
    constraint imports_status check (status in ('STAGED', 'ACTIVE'))
);

create table import_rows (
    id uuid primary key,
    import_id uuid not null references imports (id),
    row_number integer not null,
    row_class text not null,
    source_id text,
    name text,
    quantity numeric(18, 6),
    amount_paise bigint,
    product_id uuid,
    party_id uuid,
    status text not null,
    error text,
    unique (import_id, row_number),
    constraint import_rows_status check (status in ('MAPPED', 'QUARANTINED', 'HISTORICAL'))
);
