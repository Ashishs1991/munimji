create table pending_balance_questions (
    business_id uuid not null references businesses(id),
    actor_id uuid not null,
    party_name text,
    balance_side text not null check (balance_side in ('payable', 'receivable', 'both')),
    amount_paise bigint,
    expires_at timestamptz not null,
    primary key (business_id, actor_id)
);

-- Server-only clarification state; no direct browser/anonymous table access.
alter table pending_balance_questions enable row level security;
