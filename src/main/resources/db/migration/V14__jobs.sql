create table jobs (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    kind text not null,
    payload text not null,
    status text not null,
    due_at timestamptz not null,
    lease_owner text,
    lease_until timestamptz,
    attempts integer not null default 0,
    outcome text,
    completed_at timestamptz,
    constraint jobs_status check (status in ('PENDING', 'LEASED', 'DONE')),
    constraint jobs_attempts_non_negative check (attempts >= 0)
);

create index jobs_due on jobs (due_at, id)
    where status in ('PENDING', 'LEASED');
