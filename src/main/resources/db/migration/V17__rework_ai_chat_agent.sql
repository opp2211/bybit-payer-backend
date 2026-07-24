alter table ai_chat_sessions
    add column mode varchar(32),
    add column receipt_email_confirmed boolean,
    add column final_warning_sent boolean not null default false,
    add column payment_actually_sent_claimed boolean not null default false,
    add column operator_handoff_reason text,
    add column last_inactivity_reminder_at timestamptz,
    add column payment_verification_reminder_sent_at timestamptz,
    add column suggested_action varchar(48),
    add column suggested_final_warning text,
    add column last_action varchar(48),
    add column conversation_summary text,
    add column summary_updated_at timestamptz,
    add column last_summarized_message_id varchar(128);

update ai_chat_sessions
set mode = case when enabled then 'ENABLED' else 'DRY_RUN' end,
    final_warning_sent = coalesce(final_warning_confirmed, false);

alter table ai_chat_sessions
    alter column mode set not null,
    drop column enabled,
    drop column final_warning_confirmed,
    drop column unclear_replies_count,
    drop column cancellation_replies_count,
    drop column paid_without_receipt_replies_count;

alter table ai_chat_model_calls
    add column purpose varchar(32) not null default 'DECISION';
