create table ai_chat_sessions (
    id bigserial primary key,
    workspace_id bigint not null references workspaces (id),
    withdrawal_request_id bigint not null references withdrawal_requests (id),
    enabled boolean not null default true,
    status varchar(48) not null,
    current_step varchar(64) not null,
    auto_receipt_enabled boolean not null default false,
    required_receipt_email boolean not null default false,
    optional_receipt_email boolean not null default false,
    sender_first_party_confirmed boolean,
    payer_bank_confirmed boolean,
    payer_bank_name varchar(128),
    third_party_transfer_confirmed boolean,
    final_warning_confirmed boolean,
    requisites_sent_at timestamptz,
    operator_required_at timestamptz,
    last_processed_message_id varchar(128),
    last_processed_message_created_at timestamptz,
    last_receipt_check_id_handled bigint,
    unclear_replies_count integer not null default 0,
    cancellation_replies_count integer not null default 0,
    paid_without_receipt_replies_count integer not null default 0,
    suggested_messages_json text,
    suggested_reason text,
    suggested_at timestamptz,
    last_decision_summary text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uq_ai_chat_sessions_withdrawal unique (withdrawal_request_id)
);

create index idx_ai_chat_sessions_workspace_status
    on ai_chat_sessions (workspace_id, status);

create table ai_chat_model_calls (
    id bigserial primary key,
    ai_chat_session_id bigint not null references ai_chat_sessions (id),
    withdrawal_request_id bigint not null references withdrawal_requests (id),
    model varchar(128) not null,
    prompt_json text not null,
    response_json text,
    error text,
    created_at timestamptz not null
);

create index idx_ai_chat_model_calls_session_created
    on ai_chat_model_calls (ai_chat_session_id, created_at, id);
