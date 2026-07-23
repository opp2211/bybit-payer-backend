alter table withdrawal_requests
    add column amount_mode varchar(32),
    add column amount_min_rub numeric(19, 2),
    add column amount_max_rub numeric(19, 2);

update withdrawal_requests
set amount_mode = 'FIXED',
    amount_min_rub = amount_rub,
    amount_max_rub = amount_rub
where amount_mode is null;

alter table withdrawal_requests
    alter column amount_mode set not null,
    alter column amount_min_rub set not null,
    alter column amount_max_rub set not null,
    alter column amount_rub drop not null;

alter table withdrawal_requests
    alter column amount_rub type numeric(19, 2);

create index idx_withdrawal_requests_status_amount_range
    on withdrawal_requests (status, amount_min_rub, amount_max_rub);
