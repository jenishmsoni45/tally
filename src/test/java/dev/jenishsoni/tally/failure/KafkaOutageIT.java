package dev.jenishsoni.tally.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import dev.jenishsoni.tally.account.Account;

/**
 * With the app cut off from Kafka for 60 s, transfers keep answering 201, because posting only writes an outbox
 * row and the relay is what talks to Kafka. Every row posted during the cut is still unpublished when the link
 * comes back, which shows the cut reached the relay, and the relay then empties the outbox within 30 s.
 */
class KafkaOutageIT extends FailureTest {

    // From the plan: many times the producer's 3 s delivery timeout, so the relay fails batch after batch.
    private static final Duration OUTAGE = Duration.ofSeconds(60);

    // From the plan, and counted from the restore rather than from when the wait starts.
    private static final Duration DRAIN_DEADLINE = Duration.ofSeconds(30);

    // Roughly ten transfers a second, so the cut leaves a backlog several relay batches long.
    private static final Duration POST_INTERVAL = Duration.ofMillis(100);

    // Far above what the poster has left once told to stop: one transfer, bounded by the 5 s transaction
    // timeout, and one pause.
    private static final Duration POSTER_STOP_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void transfersSucceedThroughASixtySecondKafkaCutAndTheOutboxDrainsWithinThirtySeconds() throws Exception {
        // Both accounts may go negative, so no transfer is refused.
        Account from = account("CAD", true);
        Account to = account("CAD", true);
        AtomicBoolean stop = new AtomicBoolean();
        Outage outage;
        List<Sent> sent;

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<List<Sent>> poster = executor.submit(() -> postUntilStopped(from, to, stop));
            try {
                outage = cutKafka();
            } finally {
                // Also on failure, or closing the executor would wait for the poster forever.
                stop.set(true);
            }
            sent = poster.get(POSTER_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        await("every outbox row published").atMost(DRAIN_DEADLINE).until(() -> unpublished() == 0);
        Duration drain = Duration.ofNanos(System.nanoTime() - outage.restoredAt());
        long postedDuringCut = sent.stream().filter(outage::postedDuringCut).count();
        System.out.println("KafkaOutageIT posted " + postedDuringCut + " transfers during the " + OUTAGE.toSeconds()
                + " s cut; " + outage.unpublished() + " outbox rows were unpublished at restore and the outbox was"
                + " empty " + drain.toMillis() + " ms after restore");

        assertThat(sent).extracting(Sent::status).as("statuses of all %d transfers", sent.size()).containsOnly(201);
        assertThat(postedDuringCut).as("transfers posted during the cut").isPositive();
        // A row committed during the cut can reach Kafka only through the cut link, so every one of them must
        // still be unpublished. A cut that missed the relay would leave only the rows of its last run or two.
        assertThat(outage.unpublished()).as("unpublished outbox rows at restore")
                .isGreaterThanOrEqualTo(postedDuringCut);
        assertThat(drain).as("time from restore until every outbox row was published")
                .isLessThanOrEqualTo(DRAIN_DEADLINE);
    }

    // One attempt per transfer rather than transferUntilPosted, so a failed answer is counted, not retried away.
    private List<Sent> postUntilStopped(Account from, Account to, AtomicBoolean stop) throws InterruptedException {
        List<Sent> sent = new ArrayList<>();
        while (!stop.get()) {
            long sentAt = System.nanoTime();
            int status = transfer(key("outage-" + sent.size()), transferBody(from, to, 1)).status();
            sent.add(new Sent(sentAt, System.nanoTime(), status));
            Thread.sleep(POST_INTERVAL);
        }
        return sent;
    }

    private Outage cutKafka() throws IOException, InterruptedException {
        long cutAt;
        long countedAt;
        long unpublished;
        try {
            KAFKA_LINK.cut();
            cutAt = System.nanoTime();
            Thread.sleep(OUTAGE);
            countedAt = System.nanoTime();
            unpublished = unpublished();
        } finally {
            KAFKA_LINK.restore();
        }
        return new Outage(cutAt, countedAt, unpublished, System.nanoTime());
    }

    // The whole outbox rather than this test's rows, as the plan asks for it to drain to zero.
    private long unpublished() {
        return database.sql("select count(*) from outbox where published_at is null").query(Long.class).single();
    }

    /** System.nanoTime() readings taken around one transfer, and its status. */
    private record Sent(long sentAt, long answeredAt, int status) {
    }

    /** System.nanoTime() readings of the cut, and the unpublished rows counted just before the restore. */
    private record Outage(long cutAt, long countedAt, long unpublished, long restoredAt) {

        // Sent after the cut began and answered before the count, so its row was committed in between and could
        // reach Kafka only through the cut link.
        boolean postedDuringCut(Sent sent) {
            return sent.status() == 201 && sent.sentAt() - cutAt > 0 && countedAt - sent.answeredAt() > 0;
        }

    }

}
