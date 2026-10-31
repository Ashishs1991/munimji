alter table evidence drop constraint evidence_media_type;

alter table evidence add constraint evidence_media_type check (
    media_type in (
        'image/jpeg',
        'image/png',
        'image/webp',
        'application/pdf',
        'audio/ogg',
        'audio/mpeg',
        'audio/wav'
    )
);

create table draft_lines (
    id uuid primary key,
    draft_id uuid not null references drafts (id),
    line_number integer not null,
    product_id uuid,
    display_quantity numeric(18, 6),
    unit_label text,
    amount_paise bigint,
    unresolved text,
    unique (draft_id, line_number)
);
