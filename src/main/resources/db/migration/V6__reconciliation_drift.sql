-- The recount's findings: one row per account whose stored balance differs from the sum of its
-- lines. Each run appends its rows and never edits them, so the table is a history of drift.
-- found_at is the run's transaction start (now()), so all rows of one run share it.
create table reconciliation_drift (
    id         bigserial   primary key,
    account_id uuid        not null references accounts (id),
    stored     bigint      not null,
    computed   bigint      not null,
    found_at   timestamptz not null default now()
);

-- No UPDATE or DELETE: a finding is evidence, and the job reports drift rather than fixing it.
grant select, insert on reconciliation_drift to tally_app;

grant usage on sequence reconciliation_drift_id_seq to tally_app;
