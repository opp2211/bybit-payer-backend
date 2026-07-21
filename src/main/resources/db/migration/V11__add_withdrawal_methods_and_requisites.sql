alter table withdrawal_requests
    add column withdrawal_method varchar(32),
    add column recipient_card_number varchar(32),
    add column recipient_account_number varchar(32),
    add column recipient_card_tbank boolean not null default false,
    add column third_party_transfer boolean not null default true;

update withdrawal_requests
set withdrawal_method = 'SBP',
    third_party_transfer = true,
    recipient_card_tbank = false
where withdrawal_method is null;

alter table withdrawal_requests
    alter column withdrawal_method set not null,
    alter column recipient_phone drop not null,
    alter column recipient_bank drop not null,
    alter column recipient_name drop not null;

alter table email_receipt_checks
    add column parsed_recipient_card varchar(64);
