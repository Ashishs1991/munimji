alter table inbound_messages add column proposal_shown_at timestamptz;
create index inbound_messages_shown_proposal
    on inbound_messages (business_id, user_id, phone, proposal_shown_at desc)
    where proposal_shown_at is not null and status = 'REPLIED' and approval_bound = false;
