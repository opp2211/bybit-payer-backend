alter table withdrawal_requests
    add column require_sender_first_party boolean not null default false;
