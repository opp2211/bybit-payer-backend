alter table bybit_chat_message_logs
    add column client_message_id varchar(64);

create unique index uq_chat_message_client_id
    on bybit_chat_message_logs (client_message_id)
    where client_message_id is not null;

alter table email_receipt_checks
    add column pdf_content bytea;

alter table bybit_managed_ad_state
    add column reference_rate_15 numeric(19, 8);
