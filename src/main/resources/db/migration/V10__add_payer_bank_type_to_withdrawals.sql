alter table withdrawal_requests
    add column payer_bank_type varchar(32);

update withdrawal_requests
set payer_bank_type = 'TBANK_AUTO',
    queue_group_key = 'TBANK_AUTO'
where payer_bank_type is null;

alter table withdrawal_requests
    alter column payer_bank_type set not null;
