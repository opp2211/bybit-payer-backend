update withdrawal_requests
set attention_required = true,
    last_warning = coalesce(last_warning, 'Проверьте отправку реквизитов в чате ордера')
where status = 'PAYMENT_IN_PROGRESS'
  and requisites_sent_at is null
  and exists (
      select 1
      from ai_chat_sessions s
      where s.withdrawal_request_id = withdrawal_requests.id
        and s.bybit_order_id = withdrawal_requests.bybit_order_id
  );

update withdrawal_requests
set verification_started_at = null,
    attention_required = true,
    last_warning = 'Платеж ожидает ручной проверки оператором'
where status = 'PAYMENT_VERIFICATION'
  and verification_started_at is not null
  and not (payer_bank_type = 'TBANK_AUTO' and withdrawal_method in ('SBP', 'CARD_NUMBER'))
  and exists (
      select 1
      from ai_chat_sessions s
      where s.withdrawal_request_id = withdrawal_requests.id
        and s.bybit_order_id = withdrawal_requests.bybit_order_id
  );

update withdrawal_events
set message = 'Requisites sent to Bybit order chat'
where event_type = 'REQUISITES_SENT'
  and message = 'Requisites sent by AI chat agent';

delete from withdrawal_events
where event_type in (
    'AI_CHAT_STARTED',
    'AI_CHAT_MODE_CHANGED',
    'AI_CHAT_MESSAGE_SENT',
    'AI_CHAT_SUGGESTION_CREATED',
    'AI_CHAT_DISABLED',
    'AI_CHAT_OPERATOR_REQUIRED'
);

drop table ai_chat_model_calls;
drop table ai_chat_sessions;

alter table workspaces
    drop column ai_chat_agent_enabled;
