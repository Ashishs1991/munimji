create table evidence (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    draft_id uuid references drafts (id),
    media_type text not null,
    file_name text,
    sha256 text not null,
    content bytea not null,
    created_at timestamptz not null,
    constraint evidence_media_type check (
        media_type in ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')
    )
);
