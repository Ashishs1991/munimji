create table sales_coverage (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    covered_on date not null,
    unique (business_id, covered_on)
);
