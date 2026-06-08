create table ignored_email_receipts (
    id bigserial primary key,
    withdrawal_request_id bigint not null references withdrawal_requests (id) on delete cascade,
    receipt_key varchar(64) not null,
    email_message_id varchar(255),
    pdf_filename varchar(255),
    created_at timestamptz not null,
    constraint uq_ignored_email_receipts_withdrawal_key
        unique (withdrawal_request_id, receipt_key)
);

create index idx_ignored_email_receipts_withdrawal
    on ignored_email_receipts (withdrawal_request_id);
