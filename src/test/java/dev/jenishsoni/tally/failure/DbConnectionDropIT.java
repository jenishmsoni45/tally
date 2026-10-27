package dev.jenishsoni.tally.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import dev.jenishsoni.tally.account.Account;

/**
 * The app's database link is cut for a few seconds while many clients transfer at once, and then restored. Each
 * client retries a failed attempt with the same key and body until it is answered 201, as a client that cannot
 * tell whether its transfer was saved must. Every transfer ends up posted, and the checks after the test show that
 * none moved money twice. The seed fixes each client's transfer; only the interleaving and which attempts fail
 * vary from run to run.
 */
class DbConnectionDropIT extends FailureTest {

    private static final long SEED = 20261027L;
    private static final int CLIENTS = 200;
    private static final int ACCOUNTS = 10;
    private static final String CURRENCY = "CAD";
    // Every account may go negative, so no amount is refused for funds and every transfer must end up posted.
    private static final long MAX_TRANSFER = 10_000;
    // Shows that posting was under way before the cut, while most clients are still waiting for an answer.
    private static final int POSTED_BEFORE_CUT = 20;
    // Longer than the pool's 2 s connection timeout, so the requests queued for a connection fail too, not only
    // those whose connection was cut.
    private static final Duration CUT = Duration.ofSeconds(3);
    // Far longer than the cut and the reconnects after it, so a client runs out of time only if the app never
    // recovers.
    private static final Duration CLIENT_DEADLINE = Duration.ofSeconds(60);

    private final CountDownLatch go = new CountDownLatch(1);
    private final CountDownLatch firstPosted = new CountDownLatch(POSTED_BEFORE_CUT);
    private final AtomicInteger posted = new AtomicInteger();

    @Test
    void transfersRetriedThroughADatabaseCutAreAllPosted() throws Exception {
        List<Account> ledger = IntStream.range(0, ACCOUNTS).mapToObj(i -> account(CURRENCY, true)).toList();
        long start = System.nanoTime();
        List<Answer> answers;
        CutStart cut;
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Answer>> pending = IntStream.range(0, CLIENTS)
                    .mapToObj(client -> clients.submit(() -> sendOneTransfer(client, ledger)))
                    .toList();
            go.countDown();
            assertThat(firstPosted.await(CLIENT_DEADLINE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("%d transfers posted before the cut", POSTED_BEFORE_CUT)
                    .isTrue();
            cut = cutTheDatabaseLink();
            answers = finalAnswers(pending);
        }
        long totalMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        System.out.println("DbConnectionDropIT seed=" + SEED + " clients=" + CLIENTS + " postedWhenCut="
                + cut.posted() + " failedBeforeCut=" + cut.failedAttempts() + " failedAttempts=" + failedAttempts()
                + " totalMs=" + totalMillis);
        assertThat(answers).as("clients whose final answer is not 201")
                .filteredOn(answer -> answer.status() != 201)
                .isEmpty();
        assertThat(failedAnswers()).as("failed attempts that were not a 503 problem response")
                .filteredOn(answer -> answer.status() != 503 || !answer.isProblem())
                .isEmpty();
        // Counted from the cut on, so a 5xx from a busy pool before it cannot stand in for the cut.
        assertThat(failedAttempts()).as("attempts answered with a 5xx, which shows the cut reached the app")
                .isGreaterThan(cut.failedAttempts());
        assertThat(cut.posted()).as("transfers posted when the link was cut, out of %d", CLIENTS)
                .isLessThan(CLIENTS);
    }

    // The transfer comes from the seed and the client's number alone, whichever thread runs it.
    private Answer sendOneTransfer(int client, List<Account> ledger) throws InterruptedException {
        Random random = new Random(SEED + client);
        int from = random.nextInt(ACCOUNTS);
        int to = (from + 1 + random.nextInt(ACCOUNTS - 1)) % ACCOUNTS;
        long amount = 1 + random.nextLong(MAX_TRANSFER);
        Map<String, Object> body = transferBody(ledger.get(from), ledger.get(to), amount);
        go.await();
        Answer answer = transferUntilPosted(key("drop-" + client), body, CLIENT_DEADLINE);
        if (answer.status() == 201) {
            posted.incrementAndGet();
            firstPosted.countDown();
        }
        return answer;
    }

    /**
     * Cuts the link, holds the cut, then restores it, and returns how far the transfers had got once the cut was in
     * place. The link is restored even if cutting it fails, so no client is left retrying against a link that stays
     * down.
     */
    private CutStart cutTheDatabaseLink() throws IOException, InterruptedException {
        try {
            DATABASE_LINK.cut();
            CutStart start = new CutStart(posted.get(), failedAttempts());
            Thread.sleep(CUT);
            return start;
        } finally {
            DATABASE_LINK.restore();
        }
    }

    private static List<Answer> finalAnswers(List<Future<Answer>> pending)
            throws InterruptedException, ExecutionException {
        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> client : pending) {
            answers.add(client.get());
        }
        return answers;
    }

    /** Transfers posted and attempts failed once the cut was in place. */
    private record CutStart(int posted, int failedAttempts) {
    }

}
