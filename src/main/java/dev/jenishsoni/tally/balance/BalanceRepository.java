package dev.jenishsoni.tally.balance;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

    // PostgreSQL sorts before it locks, so the rows are locked in ascending account id order whatever order the
    // ids come in.
    Map<UUID, Long> findBalancesForUpdate(Collection<UUID> accountIds) {
        return jdbc.sql("""
                select account_id, balance
                from balances
                where account_id in (:accountIds)
                order by account_id
                for update
                """)
                .param("accountIds", accountIds)
                .query((rs, row) -> Map.entry(rs.getObject("account_id", UUID.class), rs.getLong("balance")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // Each (account_id, balance) pair becomes one row of the VALUES list, so all rows change in one statement.
    void updateBalances(Map<UUID, Long> balances) {
        List<Object[]> rows = balances.entrySet().stream()
                .map(balance -> new Object[] {balance.getKey(), balance.getValue()})
                .toList();
        jdbc.sql("""
                update balances b
                set balance = v.balance, updated_at = now()
                from (values :rows) as v (account_id, balance)
                where b.account_id = v.account_id
                """)
                .param("rows", rows)
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
