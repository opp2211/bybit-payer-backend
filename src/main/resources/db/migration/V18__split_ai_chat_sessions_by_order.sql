alter table ai_chat_sessions
    drop constraint uq_ai_chat_sessions_withdrawal;

alter table ai_chat_sessions
    add constraint uq_ai_chat_sessions_withdrawal_order
        unique (withdrawal_request_id, bybit_order_id);

create index idx_ai_chat_sessions_withdrawal_created
    on ai_chat_sessions (withdrawal_request_id, created_at, id);
