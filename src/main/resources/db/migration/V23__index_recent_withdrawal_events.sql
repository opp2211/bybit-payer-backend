create index idx_withdrawal_events_created_id
    on withdrawal_events (created_at, id);
