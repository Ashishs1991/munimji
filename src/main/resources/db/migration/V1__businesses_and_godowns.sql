create table businesses (
    id uuid primary key,
    name text not null,
    timezone text not null,
    currency varchar(3) not null,
    constraint businesses_currency_len check (char_length(currency) = 3)
);

create table memberships (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    auth_user_id uuid not null,
    role text not null,
    constraint memberships_role check (role in ('OWNER', 'OPERATOR', 'ACCOUNTANT')),
    constraint memberships_business_user unique (business_id, auth_user_id)
);

create index memberships_auth_user_id_idx on memberships (auth_user_id);

create table locations (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    code text not null,
    name text not null,
    active boolean not null default true,
    constraint locations_business_code unique (business_id, code)
);

create table business_contexts (
    auth_user_id uuid primary key,
    business_id uuid not null references businesses (id),
    context_version bigint not null,
    selected_at timestamptz not null,
    constraint business_contexts_version_positive check (context_version > 0)
);
