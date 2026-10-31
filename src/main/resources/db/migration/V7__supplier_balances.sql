alter table documents drop constraint documents_kind;

alter table documents add constraint documents_kind check (
    kind in ('PURCHASE', 'SALE', 'RECEIPT', 'CREDIT', 'SUPPLIER_BILL', 'SUPPLIER_PAYMENT')
);

alter table party_ledger_entries drop constraint party_ledger_reason;

alter table party_ledger_entries add constraint party_ledger_reason check (
    reason in ('SALE', 'RECEIPT', 'CREDIT', 'PURCHASE', 'PAYMENT')
);
