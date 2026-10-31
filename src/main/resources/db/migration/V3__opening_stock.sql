create table posted_operations (
    operation_id uuid primary key,
    business_id uuid not null references businesses (id),
    kind text not null,
    actor_id uuid not null
);

create table batches (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    product_id uuid not null references products (id),
    batch_label text,
    expiry_date date,
    expiry_precision text not null,
    constraint batches_expiry_precision check (expiry_precision in ('DAY', 'UNKNOWN')),
    constraint batches_expiry_known check (
        (expiry_precision = 'UNKNOWN' and expiry_date is null)
        or (expiry_precision = 'DAY' and expiry_date is not null)
    )
);

create table count_sessions (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    location_id uuid not null references locations (id),
    effective_at timestamptz not null,
    scope text not null,
    status text not null,
    confirming_operation_id uuid,
    constraint count_sessions_status check (status in ('OPEN', 'CONFIRMED'))
);

create table count_lines (
    id uuid primary key,
    session_id uuid not null references count_sessions (id),
    product_id uuid not null references products (id),
    batch_id uuid references batches (id),
    display_quantity numeric(18, 6) not null,
    unit_label text not null,
    confirmed_quantity numeric(18, 6),
    baseline_version bigint not null,
    expiry_date date,
    expiry_precision text not null,
    constraint count_lines_expiry_precision check (expiry_precision in ('DAY', 'UNKNOWN')),
    constraint count_lines_expiry_known check (
        (expiry_precision = 'UNKNOWN' and expiry_date is null)
        or (expiry_precision = 'DAY' and expiry_date is not null)
    )
);

create unique index count_lines_session_product
    on count_lines (session_id, product_id, batch_id) nulls not distinct;

create table stock_movements (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    product_id uuid not null references products (id),
    batch_id uuid references batches (id),
    location_id uuid not null references locations (id),
    ownership text not null,
    signed_base_quantity numeric(18, 6) not null,
    display_quantity numeric(18, 6) not null,
    unit_label text not null,
    factor_to_base numeric(18, 6) not null,
    reason text not null,
    operation_id uuid not null references posted_operations (operation_id),
    occurred_at timestamptz not null,
    constraint stock_movements_ownership check (ownership in ('BUSINESS', 'CUSTOMER', 'UNKNOWN')),
    constraint stock_movements_reason check (reason in ('OPENING', 'ADJUSTMENT', 'RECEIPT', 'SALE', 'RETURN'))
);

create unique index stock_movements_effect
    on stock_movements (operation_id, product_id, batch_id, location_id, ownership, reason) nulls not distinct;

create unique index stock_movements_one_opening
    on stock_movements (business_id, product_id, batch_id, location_id, ownership) nulls not distinct
    where reason = 'OPENING';

create table stock_balances (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    product_id uuid not null references products (id),
    batch_id uuid references batches (id),
    location_id uuid not null references locations (id),
    ownership text not null,
    quantity numeric(18, 6) not null,
    version bigint not null,
    constraint stock_balances_ownership check (ownership in ('BUSINESS', 'CUSTOMER', 'UNKNOWN'))
);

create unique index stock_balances_key
    on stock_balances (business_id, product_id, batch_id, location_id, ownership) nulls not distinct;
