-- Rows are written in the posting transaction, so an event exists exactly when its entry committed.
-- The relay publishes them to Kafka and then sets published_at.
create table outbox (
    id           bigserial   primary key,
    entry_id     uuid        not null,
    type         text        not null,
    payload      jsonb       not null,
    created_at   timestamptz not null default now(),
    published_at timestamptz
);

-- The relay's work queue: only rows it has not published yet.
create index outbox_unpublished_idx on outbox (id) where published_at is null;

grant select, insert on outbox to tally_app;

-- Column-level, so the app can mark a row published but never change what an event says.
-- It is also the UPDATE privilege that the relay's SELECT ... FOR UPDATE requires.
grant update (published_at) on outbox to tally_app;

grant usage on sequence outbox_id_seq to tally_app;
