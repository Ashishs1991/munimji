create table stock_thresholds (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    product_id uuid not null references products (id),
    minimum_base_quantity numeric(18, 6) not null,
    unique (business_id, product_id),
    constraint stock_thresholds_non_negative check (minimum_base_quantity >= 0)
);

create table low_stock_alerts (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    product_id uuid not null references products (id),
    threshold_quantity numeric(18, 6) not null,
    observed_quantity numeric(18, 6) not null,
    opened_on date not null,
    resolved_at timestamptz
);

create unique index low_stock_alerts_one_open
    on low_stock_alerts (business_id, product_id)
    where resolved_at is null;
