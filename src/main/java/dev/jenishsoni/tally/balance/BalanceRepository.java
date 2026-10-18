package dev.jenishsoni.tally.balance;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class BalanceRepository {

    private static final RowMapper<AccountLine> LINE_MAPPER = (rs, row) -> new AccountLine(
            rs.getLong("id"),
            rs.getObject("entry_id", UUID.class),
            rs.getString("kind"),
            rs.getString("description"),
            rs.getObject("effective_at", OffsetDateTime.class).toInstant(),
            rs.getLong("amount"),
            rs.getString("currency"));

    private final JdbcClient jdbc;

    BalanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StoredBalance> findBalance(UUID accountId) {
        return jdbc.sql("select balance, updated_at from balances where account_id = :accountId")
                .param("accountId", accountId)
                .query((rs, row) -> new StoredBalance(
                        rs.getLong("balance"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    Optional<Long> findBalanceForUpdate(UUID accountId) {
        return jdbc.sql("select balance from balances where account_id = :accountId for update")
                .param("accountId", accountId)
                .query(Long.class)
                .optional();
    }

    void updateBalance(UUID accountId, long balance) {
        jdbc.sql("update balances set balance = :balance, updated_at = now() where account_id = :accountId")
                .param("balance", balance)
                .param("accountId", accountId)
                .update();
    }

    long sumLinesCreatedAtOrBefore(UUID accountId, Instant asOf) {
        return jdbc.sql("""
                select coalesce(sum(l.amount), 0)
                from journal_lines l
                join journal_entries e on e.id = l.entry_id
                where l.account_id = :accountId and e.created_at <= :asOf
                """)
                .param("accountId", accountId)
                .param("asOf", OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    // Paging on line id is gap-free because posting inserts an account's lines while holding that
    // account's balance row lock, so line ids on one account are assigned in commit order.
    List<AccountLine> findLinesAfter(UUID accountId, long cursor, int limit) {
        return jdbc.sql("""
                select l.id, l.entry_id, e.kind, e.description, e.effective_at, l.amount, l.currency
                from journal_lines l
                join journal_entries e on e.id = l.entry_id
                where l.account_id = :accountId and l.id > :cursor
                order by l.id
                limit :limit
                """)
                .param("accountId", accountId)
                .param("cursor", cursor)
                .param("limit", limit)
                .query(LINE_MAPPER)
                .list();
    }

    record StoredBalance(long balance, Instant updatedAt) {
    }

}
