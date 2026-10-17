-- Flyway runs as the schema owner. The application connects as tally_app, which can
-- read and insert journal rows but has no UPDATE or DELETE on them.
-- The password comes from the appPassword Flyway placeholder (spring.flyway.placeholders).
-- It is applied only when the role is created; rotate it later with ALTER ROLE, not here.
do $$
begin
    if not exists (select from pg_roles where rolname = 'tally_app') then
        create role tally_app login password '${appPassword}';
    end if;
end
$$;

grant select, insert on accounts, journal_entries, journal_lines to tally_app;

-- Balances are the one mutable table: posting updates them under a row lock.
grant select, insert, update on balances to tally_app;

grant usage on sequence journal_lines_id_seq to tally_app;
