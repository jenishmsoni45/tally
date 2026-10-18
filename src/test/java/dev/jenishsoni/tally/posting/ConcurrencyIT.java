package dev.jenishsoni.tally.posting;

import static dev.jenishsoni.tally.LedgerFixtures.account;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.jenishsoni.tally.TestcontainersConfiguration;
import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;

/**
 * Many threads transfer between a few hot accounts at once. Money must be neither created nor
 * lost, every stored balance must equal the sum of its lines, and the only refusals allowed
 * are insufficient funds. The seed is printed and fixes each thread's sequence of transfers; only
 * the interleaving between threads varies from run to run.
 */
@Import(TestcontainersConfiguration.class)
// THREADS far exceeds the pool, so on a slow machine threads queue for a connection longer than
// production's 2 s fail-fast timeout. This test checks ledger correctness, not pool saturation.
@SpringBootTest(properties = "spring.datasource.hikari.connection-timeout=60000")
class ConcurrencyIT {

    private static final long SEED = 20261017L;
    private static final int THREADS = 50;
    private static final int TRANSFERS_PER_THREAD = 200;
    private static final int ACCOUNTS = 10;
    private static final long START_BALANCE = 10_000;
    // Large enough relative to START_BALANCE that some transfers are refused as overdrafts.
    private static final long MAX_TRANSFER = 3_000;
    private static final String CURRENCY = "CAD";
    // Keys are global and outlive the test, so a reused database must not see the same ones twice.
    // They do not change what the test does, so the run stays replayable from the seed.
    private static final String RUN = UUID.randomUUID().toString();

    @Autowired
    private PostingService posting;

    @Autowired
    private AccountService accounts;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void concurrentTransfersKeepTheLedgerBalanced() throws Exception {
        System.out.println("ConcurrencyIT seed=" + SEED + " threads=" + THREADS
                + " transfersPerThread=" + TRANSFERS_PER_THREAD);
        List<Account> funded = fundAccounts();
        Queue<Exception> unexpected = new ConcurrentLinkedQueue<>();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        runTransfers(funded, succeeded, refused, unexpected);

        System.out.println("ConcurrencyIT succeeded=" + succeeded + " refusedForFunds=" + refused);
        assertThat(unexpected.stream().map(Throwable::toString).distinct().toList())
                .as("distinct unexpected failures out of %d", unexpected.size())
                .isEmpty();
        assertThat(succeeded.get() + refused.get()).isEqualTo(THREADS * TRANSFERS_PER_THREAD);
        assertThat(succeeded.get()).isPositive();
        assertThat(totalBalance(funded)).isEqualTo(ACCOUNTS * START_BALANCE);
        for (Account account : funded) {
            assertThat(storedBalance(account)).as("stored balance of %s", account.id())
                    .isEqualTo(sumOfLines(account))
                    .isGreaterThanOrEqualTo(0);
        }
    }

    private List<Account> fundAccounts() {
        Account treasury = account(accounts, CURRENCY, true);
        List<Account> funded = IntStream.range(0, ACCOUNTS).mapToObj(i -> account(accounts, CURRENCY, false)).toList();
        List<LineRequest> lines = new ArrayList<>();
        funded.forEach(a -> lines.add(new LineRequest(a.id(), START_BALANCE, CURRENCY)));
        lines.add(new LineRequest(treasury.id(), -START_BALANCE * ACCOUNTS, CURRENCY));
        posting.postEntry("funding-" + RUN, new EntryRequest("funding", null, lines));
        return funded;
    }

    private void runTransfers(List<Account> funded, AtomicInteger succeeded, AtomicInteger refused,
            Queue<Exception> unexpected) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                Random random = new Random(SEED + t);
                workers.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                        // A fixed key per transfer keeps the run replayable from the seed.
                        transferOnce("cc-" + RUN + "-" + thread + "-" + i, funded, random, succeeded, refused, unexpected);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> worker : workers) {
                worker.get();
            }
        }
    }

    private void transferOnce(String key, List<Account> funded, Random random, AtomicInteger succeeded,
            AtomicInteger refused, Queue<Exception> unexpected) {
        int from = random.nextInt(ACCOUNTS);
        int to = (from + 1 + random.nextInt(ACCOUNTS - 1)) % ACCOUNTS;
        long amount = 1 + random.nextLong(MAX_TRANSFER);
        try {
            posting.postTransfer(key, new TransferRequest(
                    funded.get(from).id(), funded.get(to).id(), amount, CURRENCY, null, null));
            succeeded.incrementAndGet();
        } catch (InsufficientFundsException e) {
            refused.incrementAndGet();
        } catch (Exception e) {
            unexpected.add(e);
        }
    }

    private long totalBalance(List<Account> funded) {
        return funded.stream().mapToLong(this::storedBalance).sum();
    }

    private long storedBalance(Account account) {
        return jdbc.sql("select balance from balances where account_id = :id")
                .param("id", account.id())
                .query(Long.class)
                .single();
    }

    private long sumOfLines(Account account) {
        return jdbc.sql("select coalesce(sum(amount), 0) from journal_lines where account_id = :id")
                .param("id", account.id())
                .query(Long.class)
                .single();
    }

}
