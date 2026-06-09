delete from foreign_bybit_orders;

alter table foreign_bybit_orders
    drop column cancel_requested,
    drop column cancel_request_attempts,
    drop column cancel_requested_at,
    drop column attention_required,
    drop column last_error;
