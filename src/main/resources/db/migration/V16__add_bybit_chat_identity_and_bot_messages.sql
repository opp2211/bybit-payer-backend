alter table workspaces
    add column bybit_user_id varchar(128),
    add column bybit_account_id varchar(128),
    add column bybit_nickname varchar(128);

create table bybit_bot_chat_messages (
    id bigserial primary key,
    workspace_id bigint references workspaces (id),
    withdrawal_request_id bigint references withdrawal_requests (id) on delete cascade,
    bybit_order_id varchar(128) not null,
    msg_uuid varchar(64) not null,
    message_text text,
    created_at timestamptz not null,
    constraint uq_bybit_bot_chat_messages_uuid unique (msg_uuid)
);

create index idx_bybit_bot_chat_messages_workspace_order
    on bybit_bot_chat_messages (workspace_id, bybit_order_id);

create index idx_bybit_bot_chat_messages_withdrawal
    on bybit_bot_chat_messages (withdrawal_request_id);
