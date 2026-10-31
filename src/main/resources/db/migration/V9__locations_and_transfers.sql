alter table stock_movements drop constraint stock_movements_reason;

alter table stock_movements add constraint stock_movements_reason check (
    reason in ('OPENING', 'ADJUSTMENT', 'RECEIPT', 'SALE', 'RETURN', 'TRANSFER')
);

create table location_facts (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    label text not null,
    product_id uuid references products (id),
    location_id uuid not null references locations (id),
    ownership text not null,
    quantity numeric(18, 6),
    noted_at timestamptz not null,
    actor_id uuid not null,
    operation_id uuid not null unique references posted_operations (operation_id),
    constraint location_facts_ownership check (ownership in ('BUSINESS', 'CUSTOMER', 'UNKNOWN'))
);
