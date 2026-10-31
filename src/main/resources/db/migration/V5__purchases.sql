alter table documents drop constraint documents_kind;

alter table documents add constraint documents_kind check (
    kind in ('PURCHASE', 'SALE', 'RECEIPT', 'CREDIT', 'SUPPLIER_BILL')
);

alter table documents add column finance_status text;

update documents
set finance_status = case
    when kind = 'PURCHASE' and total_paise is null then 'PENDING'
    else 'RECORDED'
end;

alter table documents alter column finance_status set not null;

alter table documents add constraint documents_finance_status check (
    finance_status in ('PENDING', 'PARTIAL', 'BILLED', 'RECORDED')
);

alter table documents add column bill_number text;

alter table documents add column period_start date;

alter table documents add column period_end date;

alter table documents add column fingerprint text;

create unique index documents_fingerprint
    on documents (business_id, fingerprint)
    where fingerprint is not null;

create unique index documents_supplier_bill_period
    on documents (business_id, party_id, bill_number, period_start, period_end)
    where bill_number is not null;

create table bill_reviews (
    id uuid primary key,
    business_id uuid not null references businesses (id),
    supplier_id uuid not null references parties (id),
    bill_number text not null,
    period_start date not null,
    period_end date not null,
    fingerprint text not null,
    status text not null,
    constraint bill_reviews_status check (status in ('NEEDS_REVIEW'))
);
