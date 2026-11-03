alter table dialogue_state drop constraint dialogue_state_question_check;
alter table dialogue_state add constraint dialogue_state_question_check check (
    question in ('CREATE_PARTY', 'CREATE_PRODUCT', 'PRODUCT_UNIT', 'PARTY_NAME', 'PRODUCT_NAME', 'MATCH_PARTY', 'STOCK_ACTION', 'USE_PARTY', 'CREATE_GODOWN', 'GODOWN_NAME')
);
