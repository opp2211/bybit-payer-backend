alter table workspaces
    add column ai_chat_agent_enabled boolean not null default false;

alter table ai_chat_sessions
    add column enabled boolean;

update ai_chat_sessions
set enabled = mode = 'ENABLED';

alter table ai_chat_sessions
    alter column enabled set not null,
    alter column enabled set default true,
    drop column mode,
    drop column suggested_messages_json,
    drop column suggested_reason,
    drop column suggested_at,
    drop column suggested_action,
    drop column suggested_final_warning;
