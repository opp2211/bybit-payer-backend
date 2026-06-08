create table withdrawal_requests (
    id bigserial primary key,
    amount_rub numeric(19, 0) not null,
    recipient_phone varchar(32) not null,
    recipient_bank varchar(32) not null,
    recipient_name varchar(255) not null,
    status varchar(48) not null,
    attention_required boolean not null default false,
    completion_seen boolean not null default true,
    queue_group_key varchar(64),
    queue_position integer,
    bybit_order_id varchar(128),
    bybit_order_amount_rub numeric(19, 2),
    created_at timestamptz not null,
    queued_at timestamptz,
    published_at timestamptz,
    order_found_at timestamptz,
    requisites_sent_at timestamptz,
    paid_at timestamptz,
    verification_started_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    deleted_at timestamptz,
    last_error text,
    last_warning text
);

create index idx_withdrawal_requests_status_created
    on withdrawal_requests (status, created_at, id);

create index idx_withdrawal_requests_amount_status
    on withdrawal_requests (amount_rub, status);

create table withdrawal_events (
    id bigserial primary key,
    withdrawal_request_id bigint not null references withdrawal_requests (id) on delete cascade,
    event_type varchar(64) not null,
    message text not null,
    payload_json text,
    created_at timestamptz not null
);

create index idx_withdrawal_events_request_created
    on withdrawal_events (withdrawal_request_id, created_at, id);

create table bybit_managed_ad_state (
    id bigserial primary key,
    bybit_ad_id varchar(128),
    is_published boolean not null default false,
    last_rate numeric(19, 8),
    last_min_rub numeric(19, 0),
    last_max_rub numeric(19, 0),
    last_quantity_usdt numeric(19, 8),
    last_description text,
    last_updated_at timestamptz,
    last_error text
);

create table bybit_order_bindings (
    id bigserial primary key,
    bybit_order_id varchar(128) not null,
    withdrawal_request_id bigint not null references withdrawal_requests (id) on delete cascade,
    amount_rub numeric(19, 2) not null,
    status varchar(48) not null,
    created_at timestamptz not null,
    constraint uq_bybit_order_bindings_order unique (bybit_order_id),
    constraint uq_bybit_order_bindings_withdrawal unique (withdrawal_request_id)
);

create table bybit_chat_message_logs (
    id bigserial primary key,
    bybit_order_id varchar(128) not null,
    withdrawal_request_id bigint not null references withdrawal_requests (id) on delete cascade,
    message_index integer not null,
    message_text text not null,
    status varchar(48) not null,
    sent_at timestamptz,
    error text,
    constraint uq_chat_message_order_index unique (bybit_order_id, message_index)
);

create index idx_chat_message_withdrawal
    on bybit_chat_message_logs (withdrawal_request_id);

create table email_receipt_checks (
    id bigserial primary key,
    withdrawal_request_id bigint not null references withdrawal_requests (id) on delete cascade,
    bybit_order_id varchar(128),
    email_message_id varchar(255),
    email_from varchar(255),
    email_subject text,
    email_received_at timestamptz,
    pdf_filename varchar(255),
    parsed_status varchar(128),
    parsed_amount_rub numeric(19, 2),
    parsed_recipient_phone varchar(64),
    parsed_recipient_bank varchar(128),
    parsed_recipient_name varchar(255),
    parsed_operation_date varchar(128),
    parsed_operation_id varchar(128),
    parsed_receipt_number varchar(128),
    verification_status varchar(48) not null,
    verification_error text,
    created_at timestamptz not null
);

create index idx_email_receipt_checks_withdrawal_created
    on email_receipt_checks (withdrawal_request_id, created_at, id);

create table foreign_bybit_orders (
    id bigserial primary key,
    bybit_order_id varchar(128) not null,
    amount_rub numeric(19, 2) not null,
    bybit_status varchar(64),
    reason text not null,
    cancel_requested boolean not null default false,
    cancel_request_attempts integer not null default 0,
    cancel_requested_at timestamptz,
    attention_required boolean not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    last_error text,
    constraint uq_foreign_bybit_orders_order unique (bybit_order_id)
);

create index idx_foreign_bybit_orders_updated
    on foreign_bybit_orders (updated_at desc, id desc);
