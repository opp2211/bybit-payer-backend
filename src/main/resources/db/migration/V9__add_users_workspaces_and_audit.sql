create table users (
    id bigserial primary key,
    public_id varchar(7) not null,
    username varchar(32) not null,
    username_normalized varchar(32) not null,
    email varchar(255) not null,
    email_normalized varchar(255) not null,
    password_hash varchar(255) not null,
    role varchar(32) not null,
    email_verified boolean not null default false,
    enabled boolean not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uq_users_public_id unique (public_id),
    constraint uq_users_username_normalized unique (username_normalized),
    constraint uq_users_email_normalized unique (email_normalized)
);

create table workspaces (
    id bigserial primary key,
    public_id varchar(7) not null,
    name varchar(128) not null,
    owner_user_id bigint not null references users (id),
    bybit_api_key_encrypted text,
    bybit_api_key_hash varchar(64),
    bybit_api_secret_encrypted text,
    bybit_p2p_ad_id varchar(128),
    receipt_email varchar(255),
    imap_host varchar(255),
    imap_port integer,
    imap_username varchar(255),
    imap_password_encrypted text,
    enabled boolean not null default true,
    deleted_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uq_workspaces_public_id unique (public_id),
    constraint uq_workspaces_bybit_key_ad unique (bybit_api_key_hash, bybit_p2p_ad_id)
);

create index idx_workspaces_owner
    on workspaces (owner_user_id);

create table workspace_members (
    id bigserial primary key,
    workspace_id bigint not null references workspaces (id) on delete cascade,
    user_id bigint not null references users (id) on delete cascade,
    role varchar(32) not null,
    created_by_user_id bigint references users (id),
    created_at timestamptz not null,
    constraint uq_workspace_members_workspace_user unique (workspace_id, user_id)
);

create index idx_workspace_members_user
    on workspace_members (user_id);

create table audit_events (
    id bigserial primary key,
    actor_user_id bigint references users (id),
    workspace_id bigint references workspaces (id),
    action varchar(96) not null,
    subject_type varchar(64),
    subject_public_id varchar(64),
    payload_json text,
    created_at timestamptz not null
);

create index idx_audit_events_workspace_created
    on audit_events (workspace_id, created_at desc, id desc);

create index idx_audit_events_actor_created
    on audit_events (actor_user_id, created_at desc, id desc);

create table bank_aliases (
    id bigserial primary key,
    bank_id bigint not null references banks (id) on delete cascade,
    alias varchar(128) not null,
    alias_normalized varchar(128) not null,
    constraint uq_bank_aliases_alias_normalized unique (alias_normalized)
);

alter table withdrawal_requests
    add column public_id varchar(7),
    add column workspace_id bigint references workspaces (id),
    add column created_by_user_id bigint references users (id);

create unique index uq_withdrawal_requests_public_id
    on withdrawal_requests (public_id)
    where public_id is not null;

create index idx_withdrawal_requests_workspace_status_created
    on withdrawal_requests (workspace_id, status, created_at, id);

alter table withdrawal_events
    add column actor_user_id bigint references users (id),
    add column actor_type varchar(32) not null default 'SYSTEM';

alter table bybit_order_bindings
    add column workspace_id bigint references workspaces (id);

alter table bybit_order_bindings
    drop constraint uq_bybit_order_bindings_order;

create unique index uq_bybit_order_bindings_workspace_order
    on bybit_order_bindings (workspace_id, bybit_order_id)
    where workspace_id is not null;

create index idx_bybit_order_bindings_workspace_status
    on bybit_order_bindings (workspace_id, status);

alter table bybit_managed_ad_state
    add column workspace_id bigint references workspaces (id);

create unique index uq_bybit_managed_ad_state_workspace
    on bybit_managed_ad_state (workspace_id)
    where workspace_id is not null;

alter table foreign_bybit_orders
    add column workspace_id bigint references workspaces (id);

alter table foreign_bybit_orders
    drop constraint uq_foreign_bybit_orders_order;

create unique index uq_foreign_bybit_orders_workspace_order
    on foreign_bybit_orders (workspace_id, bybit_order_id)
    where workspace_id is not null;

create index idx_foreign_bybit_orders_workspace_updated
    on foreign_bybit_orders (workspace_id, updated_at desc, id desc);
