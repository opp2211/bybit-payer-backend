alter table ai_chat_sessions
    add column bybit_order_id varchar(128);

create index idx_ai_chat_sessions_bybit_order_id
    on ai_chat_sessions (bybit_order_id);
