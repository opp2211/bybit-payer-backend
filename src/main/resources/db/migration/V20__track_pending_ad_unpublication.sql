alter table bybit_managed_ad_state
    add column unpublish_pending boolean not null default false,
    add column unpublish_pending_since timestamptz,
    add column unpublish_last_attempt_at timestamptz,
    add column unpublish_next_attempt_at timestamptz,
    add column unpublish_failure_count integer not null default 0;
