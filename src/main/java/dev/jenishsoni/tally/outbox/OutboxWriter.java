package dev.jenishsoni.tally.outbox;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an event for the relay to publish. It runs inside the caller's transaction, so the event
 * commits or rolls back together with the entry it describes: Kafka never hears of an entry that
 * rolled back, and never misses one that committed.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;

    OutboxWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The payload is JSON text. It is stored as jsonb, which keeps its content but not its key order or spacing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID entryId, String type, String payload) {
        jdbc.sql("""
                insert into outbox (entry_id, type, payload)
                values (:entryId, :type, cast(:payload as jsonb))
                """)
                .param("entryId", entryId)
                .param("type", type)
                .param("payload", payload)
                .update();
    }

}
