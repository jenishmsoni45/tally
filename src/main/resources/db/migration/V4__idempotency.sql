-- A key's row is created in the posting transaction and its response is saved in the same
-- transaction, so another request can only ever see a key together with its response.
create table idempotency_keys (
    key             text        primary key,
    request_hash    text        not null,
    response_status int,
    response_body   jsonb,
    created_at      timestamptz not null default now()
);

grant select, insert, update on idempotency_keys to tally_app;
