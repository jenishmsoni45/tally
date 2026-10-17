create table accounts (
    id             uuid        primary key,
    code           text        not null unique,
    currency       char(3)     not null check (currency ~ '^[A-Z]{3}$'),
    allow_negative boolean     not null default false,
    created_at     timestamptz not null default now()
);

create table balances (
    account_id uuid        primary key references accounts (id),
    balance    bigint      not null default 0,
    updated_at timestamptz not null default now()
);
