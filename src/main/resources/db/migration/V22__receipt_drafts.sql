alter table drafts drop constraint drafts_kind;

alter table drafts add constraint drafts_kind check (
    kind is null or kind in ('SALE', 'RECEIPT')
);
