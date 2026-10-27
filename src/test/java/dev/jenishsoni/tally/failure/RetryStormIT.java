package dev.jenishsoni.tally.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.jenishsoni.tally.account.Account;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * A retry storm: 50 identical requests with one Idempotency-Key arrive at once, as from a client that retries
 * without backing off. Exactly one of them posts the entry; the other 49 wait for its key, then replay its saved
 * response, so every request gets the same 201 and the money moves once. No toxic is set and both links stay
 * healthy: the fault here is the client's own retry storm, not the database or Kafka. The meter registry is
 * shared with the other failure tests in the cached context, so the timer counts are compared before and after.
 */
class RetryStormIT extends FailureTest {

    // Five times the app's 10 connections, so most requests wait for a connection as well as for the first one's key.
    private static final int REQUESTS = 50;

    private static final long AMOUNT = 700;

    @Autowired
    private MeterRegistry registry;

    @Test
    void fiftyIdenticalRequestsAtOnceGiveOneEntryAndFortyNineIdenticalReplays() throws Exception {
        Account source = account("CAD", true);
        Account destination = account("CAD", true);
        String key = key("storm");
        Map<String, Object> body = transferBody(source, destination, AMOUNT);
        long postedBefore = transferDurations("posted");
        long replayedBefore = transferDurations("replayed");

        List<Answer> answers = sendAllAtOnce(key, body);

        long posted = transferDurations("posted") - postedBefore;
        long replayed = transferDurations("replayed") - replayedBefore;
        System.out.println("RetryStormIT: requests=" + answers.size() + " posted=" + posted + " replayed=" + replayed);

        assertThat(answers).extracting(Answer::status).as("status of every answer")
                .hasSize(REQUESTS)
                .containsOnly(201);
        assertThat(answers).extracting(Answer::body).as("body of every answer")
                .containsOnly(answers.getFirst().body());
        assertThat(posted).as("requests timed as posted").isOne();
        assertThat(replayed).as("requests timed as replayed").isEqualTo(REQUESTS - 1);
        assertThat(lineCount(source)).as("lines on the source").isOne();
        assertThat(lineCount(destination)).as("lines on the destination").isOne();
        assertThat(balance(destination)).as("destination balance").isEqualTo(AMOUNT);
    }

    // Every thread is parked at the gate before it opens, so the requests leave together rather than one by one
    // as the pool starts its threads. Each sends exactly one attempt.
    private List<Answer> sendAllAtOnce(String key, Map<String, Object> body) throws Exception {
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        List<Answer> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(REQUESTS)) {
            List<Future<Answer>> requests = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                requests.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return transfer(key, body);
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Answer> request : requests) {
                answers.add(request.get());
            }
        }
        return answers;
    }

    // Tagged meters are registered on first use, so one this context has not used yet counts as zero.
    private long transferDurations(String outcome) {
        Timer timer = registry.find("ledger.posting.duration")
                .tags("operation", "transfer", "outcome", outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private int lineCount(Account account) {
        return database.sql("select count(*) from journal_lines where account_id = :id")
                .param("id", account.id())
                .query(Integer.class)
                .single();
    }

    private long balance(Account account) {
        return database.sql("select balance from balances where account_id = :id")
                .param("id", account.id())
                .query(Long.class)
                .single();
    }

}
