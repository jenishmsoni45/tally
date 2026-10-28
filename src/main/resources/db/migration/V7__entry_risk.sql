-- What the risk check decided for an entry, so each transfer can be joined to the decision that let it
-- through. Entries posted before this migration were never checked, so the default 'none' is true for them.
alter table journal_entries
    add column decision_id uuid,
    add column risk_check  text not null default 'none',
    add constraint journal_entries_risk_check_check check (risk_check in ('decided', 'fallback', 'none')),
    -- Only an allow from the risk engine has a decision; a backup answer or an unchecked entry has none.
    add constraint journal_entries_decision_id_check check ((decision_id is not null) = (risk_check = 'decided'));

-- No grant needed: tally_app's table-level SELECT and INSERT on journal_entries (V3) cover new columns.
