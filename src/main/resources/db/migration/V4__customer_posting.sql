create table documents (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    party_id uuid references parties (id),
    kind text not null,
    occurred_on date not null,
    total_paise bigint,
    operation_id uuid not null unique references posted_operations (operation_id),
    source_document_id uuid references documents (id),
    constraint documents_kind check (kind in ('PURCHASE', 'SALE', 'RECEIPT', 'CREDIT'))
);

create table document_lines (
    id uuid primary key,
    document_id uuid not null references documents (id),
    product_id uuid not null references products (id),
    location_id uuid not null references locations (id),
    display_quantity numeric(18, 6) not null,
    unit_label text not null,
    factor_to_base numeric(18, 6) not null,
    base_quantity numeric(18, 6) not null,
    amount_paise bigint,
    restock boolean not null
);

create table party_ledger_entries (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    party_id uuid not null references parties (id),
    account_side text not null,
    signed_paise bigint not null,
    reason text not null,
    source_document_id uuid references documents (id),
    operation_id uuid not null references posted_operations (operation_id),
    effective_at timestamptz not null,
    constraint party_ledger_side check (account_side in ('RECEIVABLE', 'PAYABLE')),
    constraint party_ledger_reason check (reason in ('SALE', 'RECEIPT', 'CREDIT'))
);

create unique index party_ledger_operation_reason
    on party_ledger_entries (operation_id, account_side, reason);

create table allocations (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    source_entry_id uuid not null references party_ledger_entries (id),
    target_document_id uuid not null references documents (id),
    account_side text not null,
    amount_paise bigint not null,
    constraint allocations_positive check (amount_paise > 0),
    constraint allocations_side check (account_side in ('RECEIVABLE', 'PAYABLE'))
);

create unique index allocations_one_source
    on allocations (source_entry_id);
