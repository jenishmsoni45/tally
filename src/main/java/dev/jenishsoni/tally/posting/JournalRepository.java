package dev.jenishsoni.tally.posting;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    StoredEntry insertEntry(UUID id, String kind, String description, Instant effectiveAt) {
        return jdbc.sql("""
                insert into journal_entries (id, kind, description, effective_at)
                values (:id, :kind, :description, coalesce(:effectiveAt, now()))
                returning effective_at, created_at
                """)
                .param("id", id)
                .param("kind", kind)
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

    record StoredEntry(Instant effectiveAt, Instant createdAt) {
    }

}
