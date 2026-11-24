alter table bybit_order_bindings
    add column completion_thanks_requested boolean not null default false,
    add column completion_thanks_processed boolean not null default false;

create index idx_bybit_order_bindings_pending_thanks
    on bybit_order_bindings (id)
    where completion_thanks_requested and not completion_thanks_processed;
