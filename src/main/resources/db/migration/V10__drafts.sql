create table drafts (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    actor_id uuid not null,
    kind text,
    status text not null,
    version bigint not null,
    content_hash text not null,
    customer_id uuid,
    product_id uuid,
    location_id uuid,
    display_quantity numeric(18, 6),
    unit_label text,
    amount_paise bigint,
    occurred_on date,
    occurred_at timestamptz,
    missing_fields text not null,
    expires_at timestamptz not null,
    constraint drafts_status check (status in ('NEEDS_CLARIFICATION', 'AWAITING_APPROVAL', 'POSTED')),
    constraint drafts_kind check (kind is null or kind in ('SALE'))
);

create table approvals (
    id uuid primary key,
    draft_id uuid not null references drafts (id),
    draft_version bigint not null,
    content_hash text not null,
    actor_id uuid not null,
    approved_at timestamptz not null,
    unique (draft_id, draft_version)
);
