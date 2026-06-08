create table banks (
    id bigserial primary key,
    code varchar(32) not null,
    title varchar(128) not null,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    constraint uq_banks_code unique (code),
    constraint uq_banks_title unique (title)
);

insert into banks (code, title, sort_order)
values
    ('SBERBANK', 'Сбербанк', 10),
    ('VTB', 'ВТБ', 20),
    ('TBANK', 'Т-банк', 30);

alter table withdrawal_requests
    add constraint fk_withdrawal_requests_recipient_bank
        foreign key (recipient_bank) references banks (code);
