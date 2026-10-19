package dev.jenishsoni.tally.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka at least once: a batch whose acks or commit fail is sent again, so
 * consumers must dedupe on entry_id. SKIP LOCKED lets several relays (one per app instance) run at
 * once without two of them picking up the same row.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final String TOPIC = "ledger.entries";

    // Bounds how long one transaction holds its row locks and how many rows one failure sends again.
    static final int BATCH_SIZE = 100;

    // Longer than the producer's 3 s delivery timeout, so Kafka's own error normally arrives first, and
    // shorter than the 5 s transaction timeout, so the batch fails before its transaction does.
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(4);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = transaction;
    }

    // Starting value from the plan: an idle tick is one indexed query, and an entry reaches Kafka
    // within about 200 ms of its commit.
    @Scheduled(fixedDelay = 200)
    void relay() {
        try {
            publishBatch();
        } catch (RuntimeException e) {
            // Not catch-and-ignore: the rows stay unpublished, so the next run sends them again.
            log.warn("Outbox batch rolled back; its rows stay unpublished and are retried on the next run", e);
        }
    }

    // A template rather than @Transactional: relay() calls this on itself, which would bypass the proxy.
    int publishBatch() {
        return transaction.execute(_ -> publishPending());
    }

    private int publishPending() {
        List<PendingEvent> events = lockUnpublished();
        if (events.isEmpty()) {
            return 0;
        }
        awaitAcks(send(events));
        markPublished(events);
        return events.size();
    }

    private List<PendingEvent> lockUnpublished() {
        return jdbc.sql("""
                select id, entry_id, cast(payload as text) as payload
                from outbox
                where published_at is null
                order by id
                limit :limit
                for update skip locked
                """)
                .param("limit", BATCH_SIZE)
                .query((rs, row) -> new PendingEvent(
                        rs.getLong("id"),
                        rs.getObject("entry_id", UUID.class),
                        rs.getString("payload")))
                .list();
    }

    // All sends go out before any ack is awaited, so a batch costs one round trip rather than one per row.
    // A record the producer refuses outright, as after max.block.ms without topic metadata, makes
    // KafkaTemplate throw here, so a batch never waits out that block once per row.
    private List<CompletableFuture<SendResult<String, String>>> send(List<PendingEvent> events) {
        return events.stream()
                .map(event -> kafka.send(TOPIC, event.entryId().toString(), event.payload()))
                .toList();
    }

    private static void awaitAcks(List<CompletableFuture<SendResult<String, String>>> acks) {
        try {
            CompletableFuture.allOf(acks.toArray(new CompletableFuture<?>[0]))
                    .get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new KafkaException("Kafka did not acknowledge the outbox batch", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaException("Interrupted while waiting for Kafka to acknowledge the outbox batch", e);
        }
    }

    private void markPublished(List<PendingEvent> events) {
        jdbc.sql("update outbox set published_at = now() where id in (:ids)")
                .param("ids", events.stream().map(PendingEvent::id).toList())
                .update();
    }

    private record PendingEvent(long id, UUID entryId, String payload) {
    }

}
