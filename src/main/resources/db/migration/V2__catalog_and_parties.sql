create table products (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    sku text not null,
    name text not null,
    brand text,
    variant text,
    base_unit text not null,
    base_unit_divisible boolean not null,
    active boolean not null default true,
    tax_configuration_status text not null,
    constraint products_business_sku unique (business_id, sku),
    constraint products_tax_status check (tax_configuration_status in ('UNCONFIGURED'))
);

create table parties (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    display_name text not null,
    normalized_name text not null,
    role text not null,
    gstin text,
    constraint parties_role check (role in ('CUSTOMER', 'SUPPLIER', 'BOTH'))
);

create index parties_business_normalized_name_idx on parties (business_id, normalized_name);

create table product_aliases (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    product_id uuid not null references products (id),
    normalized_alias text not null,
    supplier_party_id uuid references parties (id)
);

create unique index product_aliases_business_alias
    on product_aliases (business_id, normalized_alias, supplier_party_id)
    nulls not distinct;

create table product_units (
    id uuid primary key,
    product_id uuid not null references products (id),
    unit_label text not null,
    factor_to_base numeric(18, 6) not null,
    approved_by uuid not null,
    constraint product_units_product_label unique (product_id, unit_label),
    constraint product_units_factor_positive check (factor_to_base > 0)
);
