package dev.jenishsoni.tally.reconcile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads balances and journal_lines with its own SQL rather than through the balance and posting
 * services on purpose: the recount must stay independent of the code it checks.
 */
@Repository
class ReconciliationRepository {

    private final JdbcClient jdbc;

    ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One statement, so balances and lines are read from one snapshot. Posting changes both in one
     * transaction, so a posting in flight is either wholly visible or not at all and can never look
     * like drift, which separate per-account queries could not promise. It takes no row locks, so
     * it never blocks a posting.
     */
    List<AccountDrift> recordDrift() {
        return jdbc.sql("""
                with drift as (
                    insert into reconciliation_drift (account_id, stored, computed)
                    select b.account_id, b.balance, coalesce(sum(l.amount), 0)
                    from balances b
                    left join journal_lines l on l.account_id = b.account_id
                    group by b.account_id, b.balance
                    having b.balance <> coalesce(sum(l.amount), 0)
                    returning account_id, stored, computed, found_at
                )
                select account_id, stored, computed, found_at
                from drift
                order by account_id
                """)
                .query((rs, row) -> new AccountDrift(
                        rs.getObject("account_id", UUID.class),
                        rs.getLong("stored"),
                        rs.getLong("computed"),
                        rs.getObject("found_at", OffsetDateTime.class).toInstant()))
                .list();
    }

}
