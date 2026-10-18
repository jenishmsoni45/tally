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

-- One reversal per entry: a second one fails here even if the service's own check were bypassed.
create unique index journal_entries_reverses_id_key on journal_entries (reverses_id)
    where reverses_id is not null;
