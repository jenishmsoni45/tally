-- Lets journal_lines reference an account together with its currency.
alter table accounts add constraint accounts_id_currency_key unique (id, currency);

create table journal_entries (
    id           uuid        primary key,
    kind         text        not null check (kind in ('transfer', 'entry', 'reversal')),
    reverses_id  uuid        references journal_entries (id),
    description  text,
    effective_at timestamptz not null,
    created_at   timestamptz not null default now()
);

create table journal_lines (
    id         bigserial primary key,
    entry_id   uuid      not null references journal_entries (id),
    account_id uuid      not null,
    amount     bigint    not null check (amount <> 0),
    currency   char(3)   not null check (currency ~ '^[A-Z]{3}$'),
    -- A line must be in its account's currency, or the stored balance would mix currencies.
    foreign key (account_id, currency) references accounts (id, currency)
);

create index journal_lines_account_id_id_idx on journal_lines (account_id, id);

-- The balance trigger below sums the lines of one entry on every inserted line.
create index journal_lines_entry_id_idx on journal_lines (entry_id);

create function assert_entry_balanced() returns trigger
language plpgsql as $$
declare
    unbalanced record;
begin
    select currency, sum(amount) as total
    into unbalanced
    from journal_lines
    where entry_id = new.entry_id
    group by currency
    having sum(amount) <> 0
    limit 1;

    if found then
        raise exception 'journal entry % does not balance in %: lines sum to %',
            new.entry_id, unbalanced.currency, unbalanced.total
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

-- Deferred so an entry's lines can be inserted one by one; the check runs at commit.
create constraint trigger journal_lines_balanced
    after insert on journal_lines
    deferrable initially deferred
    for each row execute function assert_entry_balanced();

create function assert_entry_has_lines() returns trigger
language plpgsql as $$
begin
    if not exists (select from journal_lines where entry_id = new.id) then
        raise exception 'journal entry % has no lines', new.id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

-- An entry with no lines would pass the balance check trivially, so it is refused at commit.
create constraint trigger journal_entries_have_lines
    after insert on journal_entries
    deferrable initially deferred
    for each row execute function assert_entry_has_lines();
