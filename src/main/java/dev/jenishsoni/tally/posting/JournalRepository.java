package dev.jenishsoni.tally.posting;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JournalRepository {

    private final JdbcClient jdbc;

    JournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A missing effectiveAt becomes the transaction's start time. */
    StoredEntry insertEntry(UUID id, String kind, UUID reversesId, String description, Instant effectiveAt) {
        return jdbc.sql("""
                insert into journal_entries (id, kind, reverses_id, description, effective_at)
                values (:id, :kind, :reversesId, :description, coalesce(:effectiveAt, now()))
                returning effective_at, created_at
                """)
                .param("id", id)
                .param("kind", kind)
                .param("reversesId", reversesId)
                .param("description", description)
                .param("effectiveAt", effectiveAt == null ? null : OffsetDateTime.ofInstant(effectiveAt, ZoneOffset.UTC))
                .query((rs, row) -> new StoredEntry(
                        rs.getObject("effective_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    long insertLine(UUID entryId, LineRequest line) {
        return jdbc.sql("""
                insert into journal_lines (entry_id, account_id, amount, currency)
                values (:entryId, :accountId, :amount, :currency)
                returning id
                """)
                .param("entryId", entryId)
                .param("accountId", line.accountId())
                .param("amount", line.amount())
                .param("currency", line.currency())
                .query(Long.class)
                .single();
    }

    Optional<JournalEntry> findEntry(UUID id) {
        return jdbc.sql("""
                select kind, reverses_id, description, effective_at, created_at
                from journal_entries
                where id = :id
                """)
                .param("id", id)
                .query((rs, row) -> new EntryRow(
                        rs.getString("kind"),
                        rs.getObject("reverses_id", UUID.class),
                        rs.getString("description"),
                        rs.getObject("effective_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional()
                .map(row -> new JournalEntry(id, row.kind(), row.reversesId(), row.description(),
                        row.effectiveAt(), row.createdAt(), findLines(id)));
    }

    boolean existsReversalOf(UUID entryId) {
        return jdbc.sql("select exists (select from journal_entries where reverses_id = :entryId)")
                .param("entryId", entryId)
                .query(Boolean.class)
                .single();
    }

    private List<JournalLine> findLines(UUID entryId) {
        return jdbc.sql("select id, account_id, amount, currency from journal_lines where entry_id = :entryId order by id")
                .param("entryId", entryId)
                .query((rs, row) -> new JournalLine(
                        rs.getLong("id"),
                        rs.getObject("account_id", UUID.class),
                        rs.getLong("amount"),
                        rs.getString("currency")))
                .list();
    }

    record StoredEntry(Instant effectiveAt, Instant createdAt) {
    }

    private record EntryRow(String kind, UUID reversesId, String description, Instant effectiveAt,
            Instant createdAt) {
    }

}
