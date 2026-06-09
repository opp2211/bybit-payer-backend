alter table bybit_order_bindings
    drop constraint uq_bybit_order_bindings_withdrawal;

alter table withdrawal_requests
    add column bybit_order_quantity_usdt numeric(19, 8),
    add column bybit_order_fee_usdt numeric(19, 8);

alter table bybit_managed_ad_state
    add column last_rate_source_position integer,
    add column next_rate_source_position integer,
    add column reference_rate_7 numeric(19, 8),
    add column reference_rate_7_with_fee numeric(19, 8);

create index idx_bybit_order_bindings_withdrawal_status
    on bybit_order_bindings (withdrawal_request_id, status);
