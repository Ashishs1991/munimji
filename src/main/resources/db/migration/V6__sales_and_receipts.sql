create table invoice_counters (
    business_id uuid primary key references businesses (id),
    last_number bigint not null
);

create table invoice_numbers (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    number bigint not null,
    document_id uuid not null unique references documents (id),
    status text not null,
    constraint invoice_numbers_status check (status in ('ISSUED', 'CANCELED')),
    unique (business_id, number)
);

alter table documents add column payment_method text;
