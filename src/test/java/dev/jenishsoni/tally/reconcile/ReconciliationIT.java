package dev.jenishsoni.tally.reconcile;

import static dev.jenishsoni.tally.LedgerFixtures.account;
import static dev.jenishsoni.tally.TestcontainersConfiguration.ADMIN_API_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.jenishsoni.tally.TestcontainersConfiguration;
import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.posting.PostingService;
import dev.jenishsoni.tally.posting.TransferRequest;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The recount flags exactly the accounts whose stored balance differs from their lines, and only those.
 * The database is shared with every other test class, some of which insert raw lines without touching
 * balances, so each test compares against what it set up rather than expecting an empty report.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class ReconciliationIT {

    private static final String CURRENCY = "CAD";
    private static final String REFUSAL = "A valid admin API key is required";
    private static final int POSTERS = 4;
    private static final int TRANSFERS_PER_POSTER = 100;
    private static final int MIN_RUNS_DURING_POSTING = 3;

    @Autowired
    private RestTestClient client;

    @Autowired
    private ReconciliationJob job;

    @Autowired
    private PostingService posting;

    @Autowired
    private AccountService accounts;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void aBalanceChangedOutsideTheLedgerIsTheOnlyNewlyFlaggedAccount() {
        Account a = account(accounts, CURRENCY, true);
        Account b = account(accounts, CURRENCY, true);
        Account c = account(accounts, CURRENCY, true);
        transfer(a, b, 500);
        transfer(b, c, 200);
        Set<UUID> baseline = driftedIds(job.run());
        assertThat(baseline).as("accounts posted through the service").doesNotContain(a.id(), b.id(), c.id());
        JdbcClient owner = ownerConnection();

        shiftStoredBalance(owner, b, 1);
        try {
            ReconciliationReport report = reconcileOverHttp();

            Set<UUID> expected = new HashSet<>(baseline);
            expected.add(b.id());
            assertThat(driftedIds(report)).isEqualTo(expected);
            assertThat(report.driftedAccounts()).isEqualTo(report.drift().size());
            AccountDrift corrupted = driftOf(report, b);
            assertThat(corrupted.computed()).as("the sum of b's lines: +500 - 200").isEqualTo(300);
            assertThat(corrupted.stored()).isEqualTo(corrupted.computed() + 1);
            assertThat(recordedRows(owner, corrupted)).isOne();
            assertThat(registry.get(ReconciliationJob.DRIFT_GAUGE).gauge().value())
                    .isEqualTo(report.driftedAccounts());
        } finally {
            // Later test classes share the database and must not inherit this drift.
            shiftStoredBalance(owner, b, -1);
        }
    }

    /**
     * Each poster walks the accounts in a fixed rotation, so the transfers are the same on every run
     * and there is no seed; only the interleaving with the recounts varies.
     */
    @Test
    void postingsCommittedDuringARecountAreNeverReportedAsDrift() throws Exception {
        List<Account> busy = IntStream.range(0, POSTERS).mapToObj(i -> account(accounts, CURRENCY, true)).toList();
        Set<UUID> busyIds = busy.stream().map(Account::id).collect(Collectors.toSet());
        int total = POSTERS * TRANSFERS_PER_POSTER;
        AtomicInteger committed = new AtomicInteger();
        List<ReconciliationReport> reports = new ArrayList<>();
        int runsDuringPosting = 0;

        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(POSTERS)) {
            List<Future<?>> posters = new ArrayList<>();
            for (int p = 0; p < POSTERS; p++) {
                int poster = p;
                posters.add(pool.submit(() -> {
                    go.await();
                    postRotation(busy, poster, committed);
                    return null;
                }));
            }
            go.countDown();
            while (!allDone(posters)) {
                int committedBefore = committed.get();
                reports.add(job.run());
                if (committedBefore > 0 && committed.get() < total) {
                    runsDuringPosting++;
                }
            }
            for (Future<?> poster : posters) {
                poster.get();
            }
        }

        System.out.println("ReconciliationIT recounts=" + reports.size() + " duringPosting=" + runsDuringPosting);
        assertThat(committed).hasValue(total);
        assertThat(runsDuringPosting).as("recounts that ran while transfers were committing")
                .isGreaterThanOrEqualTo(MIN_RUNS_DURING_POSTING);
        assertThat(reports.stream().flatMap(report -> driftedIds(report).stream()))
                .doesNotContainAnyElementsOf(busyIds);
    }

    @Test
    void anAdminRequestWithoutAKeyIs401WithABearerChallenge() {
        client.post().uri("/admin/reconcile")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody()
                .jsonPath("$.detail").isEqualTo(REFUSAL);
    }

    @ParameterizedTest
    @MethodSource("wrongAuthorizations")
    void anAdminRequestWithAWrongKeyOrSchemeIs401(String authorization) {
        client.post().uri("/admin/reconcile")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.detail").isEqualTo(REFUSAL);
    }

    @Test
    void anAdminRequestWithTheKeyIs200WithAReport() {
        client.post().uri("/admin/reconcile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_API_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.driftedAccounts").isNumber();
    }

    static Stream<String> wrongAuthorizations() {
        return Stream.of(
                "Bearer not-the-key",
                "Bearer " + "x".repeat(ADMIN_API_KEY.length()),
                "Basic " + ADMIN_API_KEY);
    }

    private void transfer(Account from, Account to, long amount) {
        posting.postTransfer(UUID.randomUUID().toString(),
                new TransferRequest(from.id(), to.id(), amount, CURRENCY, null, null));
    }

    private void postRotation(List<Account> busy, int poster, AtomicInteger committed) {
        for (int i = 0; i < TRANSFERS_PER_POSTER; i++) {
            Account from = busy.get((poster + i) % POSTERS);
            Account to = busy.get((poster + i + 1) % POSTERS);
            transfer(from, to, 1);
            committed.incrementAndGet();
        }
    }

    private static boolean allDone(List<Future<?>> futures) {
        return futures.stream().allMatch(Future::isDone);
    }

    private ReconciliationReport reconcileOverHttp() {
        return client.post().uri("/admin/reconcile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_API_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReconciliationReport.class)
                .returnResult()
                .getResponseBody();
    }

    // The schema owner, standing in for someone who edits the database directly, outside the application.
    private JdbcClient ownerConnection() {
        return JdbcClient.create(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private static void shiftStoredBalance(JdbcClient owner, Account account, long delta) {
        owner.sql("update balances set balance = balance + :delta where account_id = :id")
                .param("delta", delta)
                .param("id", account.id())
                .update();
    }

    private static int recordedRows(JdbcClient owner, AccountDrift drift) {
        return owner.sql("""
                select count(*)
                from reconciliation_drift
                where account_id = :id and stored = :stored and computed = :computed and found_at = :foundAt
                """)
                .param("id", drift.accountId())
                .param("stored", drift.stored())
                .param("computed", drift.computed())
                .param("foundAt", utc(drift.foundAt()))
                .query(Integer.class)
                .single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static AccountDrift driftOf(ReconciliationReport report, Account account) {
        return report.drift().stream()
                .filter(drift -> drift.accountId().equals(account.id()))
                .findFirst()
                .orElseThrow();
    }

    private static Set<UUID> driftedIds(ReconciliationReport report) {
        return report.drift().stream().map(AccountDrift::accountId).collect(Collectors.toSet());
    }

}
