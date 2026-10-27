package dev.jenishsoni.tally.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import dev.jenishsoni.tally.account.Account;
import tools.jackson.databind.json.JsonMapper;

/**
 * A database that answers more slowly than the transaction timeout fails every transfer with a clean 503 problem
 * response that names nothing internal, and the failed transfers leave no lines, saved keys or balance changes
 * behind.
 */
@TestPropertySource(properties =
        // A context of its own with a 1 s transaction timeout instead of the 5 s default. The base class's
        // containers are static and shared, so this costs no new containers.
        "spring.transaction.default-timeout=1s")
// Closed after the class, so its relay and pool stop rather than run on beside the other failure tests' context,
// draining the same outbox and changing what those tests measure.
@DirtiesContext
class SlowDbIT extends FailureTest {

    // Every round trip takes longer than the 1 s transaction timeout, so no transaction can finish in time.
    private static final Duration LATENCY = Duration.ofMillis(2000);

    // Fewer than the pool's 10 connections, so each transfer waits on the database, not on the pool.
    private static final int TRANSFERS = 5;

    @Autowired
    private JsonMapper json;

    @Test
    void transfersOnASlowDatabaseFailWithA503ProblemAndLeaveNoPartialEntries() throws Exception {
        Account source = account("CAD", true);
        Account destination = account("CAD", true);
        List<String> keys = IntStream.range(0, TRANSFERS).mapToObj(i -> key("slow-" + i)).toList();
        Map<String, Object> body = transferBody(source, destination, 100);

        List<Attempt> attempts;
        DATABASE_LINK.slow(LATENCY);
        try {
            attempts = sendAtOnce(keys, body);
        } finally {
            DATABASE_LINK.restore();
        }
        System.out.println("SlowDbIT: " + LATENCY.toMillis() + " ms latency, answers: " + attempts.stream()
                .map(attempt -> attempt.answer().status() + " in " + attempt.millis() + " ms")
                .collect(Collectors.joining(", ")));

        // The ledger is checked first, so a wrong answer still shows whether the transfers left anything behind.
        assertNoPartialEntries(List.of(source.id(), destination.id()), keys);
        attempts.forEach(this::assertCleanServiceUnavailable);
    }

    // Started together so every transfer is in flight while the link is slow; one attempt each, never retried.
    private List<Attempt> sendAtOnce(List<String> keys, Map<String, Object> body) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Attempt>> requests = new ArrayList<>();
            for (String key : keys) {
                requests.add(threads.submit(() -> {
                    go.await();
                    return timedTransfer(key, body);
                }));
            }
            go.countDown();
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> request : requests) {
                attempts.add(request.get());
            }
            return attempts;
        }
    }

    private Attempt timedTransfer(String key, Map<String, Object> body) {
        long start = System.nanoTime();
        Answer answer = transfer(key, body);
        return new Attempt(key, answer, Duration.ofNanos(System.nanoTime() - start).toMillis());
    }

    private void assertNoPartialEntries(List<UUID> accountIds, List<String> keys) {
        assertThat(database.sql("select count(*) from journal_lines where account_id in (:accounts)")
                .param("accounts", accountIds)
                .query(Integer.class)
                .single()).as("lines on the test's accounts").isZero();
        assertThat(database.sql("select key from idempotency_keys where key in (:keys)")
                .param("keys", keys)
                .query(String.class)
                .list()).as("idempotency keys saved").isEmpty();
        assertThat(database.sql("select balance from balances where account_id in (:accounts)")
                .param("accounts", accountIds)
                .query(Long.class)
                .list()).as("balances of the test's accounts").hasSize(accountIds.size()).containsOnly(0L);
    }

    private void assertCleanServiceUnavailable(Attempt attempt) {
        Answer answer = attempt.answer();
        assertThat(answer.status()).as("status for %s", attempt.key()).isEqualTo(503);
        assertThat(answer.contentType()).as("content type for %s", attempt.key())
                .isNotNull()
                .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                        "compatible with " + MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(answer.body()).as("body for %s, which must name nothing internal", attempt.key())
                .isNotBlank()
                .doesNotContainIgnoringCase("exception", "sql")
                .doesNotContain("org.", "java.", "\tat ");
        assertThat(json.readTree(answer.body()).path("status").asInt()).as("problem status for %s", attempt.key())
                .isEqualTo(503);
    }

    /** One transfer's answer and how long its client waited for it. */
    private record Attempt(String key, Answer answer, long millis) {
    }

}
