package dev.jenishsoni.tally.risk;

import static dev.jenishsoni.tally.LedgerFixtures.account;
import static dev.jenishsoni.tally.risk.FakeGuard.decided;
import static dev.jenishsoni.tally.risk.FakeGuard.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestClient;

import dev.jenishsoni.tally.TestcontainersConfiguration;
import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.idempotency.RequestHash;
import dev.jenishsoni.tally.posting.JournalEntry;
import dev.jenishsoni.tally.posting.TransferRequest;
import dev.jenishsoni.tally.risk.RiskResult.NoDecision;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The risk check against a fake risk engine (docs/risk-decision-call.md), with real PostgreSQL and Kafka. The fake
 * keeps the backup answer at hold in every reply, except where a test changes it and changes it back, so the tests
 * can run in any order in one shared context. Tests switch off trace export by default, and with it the propagation
 * of trace headers; tracing stays on here as in production, so the request the fake receives carries traceparent.
 */
@AutoConfigureTracing
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "tally.risk.enabled=true", "tally.risk.timeout=" + RiskDecisionIT.TIMEOUT_MS })
@AutoConfigureRestTestClient
class RiskDecisionIT {

    // Milliseconds. Far above the 100 ms default, so a test that expects a decision never meets a timeout on a
    // cold JVM or a busy CI runner. The bounds below are stated relative to it, so they test the same rule.
    static final long TIMEOUT_MS = 1000;

    private static final Duration TIMEOUT = Duration.ofMillis(TIMEOUT_MS);

    // How far past the timeout tally may answer: its own work around the call (the key lookup and the response)
    // and thread scheduling. Well below the timeout, so a client that waited for the timeout twice would fail.
    private static final Duration MARGIN = Duration.ofMillis(300);

    // The timeout of a client a test builds itself (standaloneClient): long enough that the calls a test leaves
    // waiting on the fake never time out first.
    private static final Duration STANDALONE_TIMEOUT = Duration.ofSeconds(10);

    private static final String RISK_DURATION = "ledger.risk.duration";

    private static final FakeGuard guard = startGuard();

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccountService accounts;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JsonMapper json;

    @DynamicPropertySource
    static void riskUrl(DynamicPropertyRegistry registry) {
        registry.add("tally.risk.url", guard::url);
    }

    @AfterEach
    void resetGuard() {
        guard.reset();
    }

    @AfterAll
    static void closeGuard() {
        guard.close();
    }

    @Test
    void allowPostsWithTheDecisionOnTheEntryAndItsEvent() {
        UUID decisionId = UUID.randomUUID();
        guard.answerWith(decided(decisionId, "allow", "hold"));
        Account from = account(accounts, "CAD", true);
        Account to = account(accounts, "CAD", false);

        JournalEntry entry = transfer(key(), from, to, 2500).expectStatus().isCreated()
                .expectBody(JournalEntry.class).returnResult().getResponseBody();

        assertThat(entry.decisionId()).isEqualTo(decisionId);
        assertThat(entry.riskCheck()).isEqualTo("decided");
        assertThat(storedRisk(entry.id())).containsExactly(decisionId.toString(), "decided");
        assertThat(outboxRisk(entry.id())).containsExactly(decisionId.toString(), "decided");
        assertThat(balance(to)).isEqualTo(2500);
    }

    @Test
    void holdIsA403WithTheDecisionAndPostsNothing() {
        assertRefusedAndAskedAgainOnRetry("hold", "/problems/transfer-held");
    }

    @Test
    void blockIsA403WithTheDecisionAndPostsNothing() {
        assertRefusedAndAskedAgainOnRetry("block", "/problems/transfer-blocked");
    }

    @Test
    void aGuardThatNeverAnswersMeetsTheTimeoutAndTheBackupAnswer() {
        assertBackupHoldWithinTheTimeout(guard.silent());
    }

    @Test
    void aGuardThatAnswersAfterTwiceTheTimeoutMeetsTheTimeoutAndTheBackupAnswer() {
        assertBackupHoldWithinTheTimeout(guard.after(TIMEOUT.multipliedBy(2), decided(UUID.randomUUID(), "allow",
                "hold")));
    }

    @Test
    void aGuardThatSendsHeadersButNoBodyMeetsTheTimeoutAndTheBackupAnswer() {
        assertBackupHoldWithinTheTimeout(guard.headersOnly());
    }

    @Test
    void aRefusedConnectionUsesTheBackupAnswer() throws IOException {
        guard.stop();
        try {
            assertBackupHold("unavailable");
        } finally {
            guard.start();
        }
    }

    @Test
    void a500UsesTheBackupAnswer() {
        guard.answerWith(json(500, "{\"error\":\"boom\"}"));
        assertBackupHold("unavailable");
    }

    @Test
    void aBodyThatIsNotJsonUsesTheBackupAnswer() {
        guard.answerWith(json(200, "not json"));
        assertBackupHold("invalid");
    }

    @Test
    void aDecisionOutsideTheListUsesTheBackupAnswer() {
        guard.answerWith(decided(UUID.randomUUID(), "maybe", "hold"));
        assertBackupHold("invalid");
    }

    @Test
    void aRememberedBackupAllowPostsTheTransferUnchecked() {
        Account from = account(accounts, "CAD", true);
        Account to = account(accounts, "CAD", false);
        guard.answerWith(decided(UUID.randomUUID(), "allow", "allow"));
        try {
            transfer(key(), from, to, 100).expectStatus().isCreated();
            guard.answerWith(guard.silent());

            JournalEntry entry = transfer(key(), from, to, 200).expectStatus().isCreated()
                    .expectBody(JournalEntry.class).returnResult().getResponseBody();

            assertThat(entry.riskCheck()).isEqualTo("fallback");
            assertThat(entry.decisionId()).isNull();
            assertThat(storedRisk(entry.id())).containsExactly(null, "fallback");
            assertThat(outboxRisk(entry.id())).containsExactly(null, "fallback");
            assertThat(balance(to)).isEqualTo(300);
        } finally {
            restoreBackupHold(from, to);
        }
    }

    // The 503 cases above meet hold whether it is the default or a remembered reply's, since every reply here names
    // hold; a client of its own with another default shows which one applies before the first reply.
    @Test
    void theBackupAnswerIsTheConfiguredDefaultUntilAReplyNamesOne() {
        RiskClient risk = standaloneClient(Decision.BLOCK);
        assertThat(risk.backupAnswer()).as("before any reply").isEqualTo(Decision.BLOCK);

        ask(risk);

        assertThat(risk.backupAnswer()).as("after a reply naming hold").isEqualTo(Decision.HOLD);
    }

    @Test
    void aGuardConflictIsTheKeyConflict() {
        guard.answerWith(json(409, "{\"title\":\"Conflict\",\"status\":409}"));
        Account from = account(accounts, "CAD", true);
        Account to = account(accounts, "CAD", false);
        String key = key();

        transfer(key, from, to, 700).expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Idempotency-Key was already used for a different request");

        assertNothingPosted(key, from, to);
    }

    @Test
    void aKeyWithASavedResponseIsReplayedWithoutAskingTheGuard() {
        Account from = account(accounts, "CAD", true);
        Account to = account(accounts, "CAD", false);
        String key = key();
        JournalEntry first = transfer(key, from, to, 900).expectStatus().isCreated()
                .expectBody(JournalEntry.class).returnResult().getResponseBody();
        guard.reset();
        guard.answerWith(decided(UUID.randomUUID(), "block", "hold"));

        JournalEntry replayed = transfer(key, from, to, 900).expectStatus().isCreated()
                .expectBody(JournalEntry.class).returnResult().getResponseBody();

        assertThat(replayed).isEqualTo(first);
        assertThat(guard.received()).isEmpty();
        assertThat(balance(to)).isEqualTo(900);
    }

    @Test
    void theGuardReceivesExactlyTheContractFields() {
        Account from = account(accounts, "CAD", true);
        Account to = account(accounts, "CAD", false);
        String key = key();

        Instant before = Instant.now();
        transfer(key, from, to, 12500).expectStatus().isCreated();
        Instant after = Instant.now();

        assertThat(guard.received()).hasSize(1);
        FakeGuard.Received request = guard.received().getFirst();
        assertThat(request.body()).containsOnlyKeys("requestId", "fromAccountId", "toAccountId", "amount",
                "currency", "requestedAt");
        assertThat(request.body()).containsEntry("requestId", key)
                .containsEntry("fromAccountId", from.id().toString())
                .containsEntry("toAccountId", to.id().toString())
                .containsEntry("amount", 12500)
                .containsEntry("currency", "CAD");
        Instant requestedAt = Instant.parse((String) request.body().get("requestedAt"));
        assertThat(requestedAt).isBetween(before, after);
        // W3C trace context: version, trace id, parent span id and flags.
        assertThat(request.traceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    @Test
    void aTransferWaitingOnTheGuardHoldsNoLockOrTransaction() throws Exception {
        Account from = account(accounts, "CAD", true);
        Account to = account(accounts, "CAD", false);
        String slowKey = key();
        CountDownLatch fastCommitted = new CountDownLatch(1);
        guard.answerWith((exchange, request) -> {
            if (slowKey.equals(request.requestId())) {
                fastCommitted.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            decided(UUID.randomUUID(), "allow", "hold").answer(exchange, request);
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JournalEntry> slow = executor.submit(() -> transfer(slowKey, from, to, 100)
                    .expectStatus().isCreated()
                    .expectBody(JournalEntry.class).returnResult().getResponseBody());
            await("the slow transfer reached the guard").atMost(TIMEOUT)
                    .until(() -> guard.callsFor(slowKey) == 1);

            JournalEntry fast = transfer(key(), from, to, 200).expectStatus().isCreated()
                    .expectBody(JournalEntry.class).returnResult().getResponseBody();
            boolean slowStillWaiting = !slow.isDone();
            fastCommitted.countDown();

            assertThat(slowStillWaiting).as("the slow transfer was still waiting on the guard").isTrue();
            assertThat(fast.riskCheck()).isEqualTo("decided");
            assertThat(slow.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).riskCheck()).isEqualTo("decided");
        }
        assertThat(balance(to)).isEqualTo(300);
    }

    @Test
    void aCallOverTheCapIsNoDecisionAtOnceWithoutAskingTheGuard() throws Exception {
        RiskClient risk = standaloneClient(Decision.HOLD);
        guard.answerWith(guard.silent());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RiskResult>> waiting = IntStream.range(0, RiskClient.MAX_CALLS_IN_FLIGHT)
                    .mapToObj(_ -> executor.submit(() -> ask(risk)))
                    .toList();
            await("every call slot holds a call the fake received").atMost(STANDALONE_TIMEOUT)
                    .until(() -> guard.received().size() == RiskClient.MAX_CALLS_IN_FLIGHT);

            long started = System.nanoTime();
            RiskResult overTheCap = ask(risk);
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(overTheCap).isEqualTo(new NoDecision(NoDecision.Reason.UNAVAILABLE));
            assertThat(took).isLessThan(MARGIN);
            assertThat(guard.received()).hasSize(RiskClient.MAX_CALLS_IN_FLIGHT);
            guard.reset();
            for (Future<RiskResult> call : waiting) {
                call.get(STANDALONE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        assertThat(ask(risk)).as("a call once the slots are free").isInstanceOf(RiskResult.Decided.class);
    }

    @Test
    void anIdleConnectionIsReusedAndClosedAfterTheKeepAlive() throws InterruptedException {
        assertThat(System.getProperty(RiskClient.KEEP_ALIVE_PROPERTY))
                .as("the test JVM keeps idle connections as long as TallyApplication.main makes tally keep them")
                .isEqualTo(String.valueOf(RiskClient.KEEP_ALIVE.toSeconds()));
        RiskClient risk = standaloneClient(Decision.HOLD);

        ask(risk);
        ask(risk);
        // The fake keeps an idle connection for 30 s, so a new connection after this wait is tally's doing.
        Thread.sleep(RiskClient.KEEP_ALIVE.plusSeconds(1));
        ask(risk);

        List<Integer> ports = guard.received().stream().map(FakeGuard.Received::clientPort).toList();
        assertThat(ports).hasSize(3);
        assertThat(ports.get(1)).as("the second call's connection").isEqualTo(ports.get(0));
        assertThat(ports.get(2)).as("the connection of a call after the keep-alive").isNotEqualTo(ports.get(0));
    }

    @Test
    void entriesAndReversalsNeverAskTheGuard() {
        Account from = account(accounts, "CAD", true);
        Account to = account(accounts, "CAD", false);
        JournalEntry checked = transfer(key(), from, to, 400).expectStatus().isCreated()
                .expectBody(JournalEntry.class).returnResult().getResponseBody();
        guard.reset();

        JournalEntry entry = client.post().uri("/entries")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of(
                        Map.of("accountId", from.id(), "amount", -50, "currency", "CAD"),
                        Map.of("accountId", to.id(), "amount", 50, "currency", "CAD"))))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(JournalEntry.class).returnResult().getResponseBody();
        JournalEntry reversal = client.post().uri("/entries/{id}/reverse", checked.id())
                .header("Idempotency-Key", key())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(JournalEntry.class).returnResult().getResponseBody();

        assertThat(guard.received()).isEmpty();
        assertThat(List.of(entry, reversal)).allSatisfy(posted -> {
            assertThat(posted.riskCheck()).isEqualTo("none");
            assertThat(posted.decisionId()).isNull();
        });
    }

    @Test
    void theSchemaRefusesADecidedEntryWithoutADecisionAndADecisionWithoutDecided() {
        assertThatThrownBy(() -> insertEntry(null, "decided"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("journal_entries_decision_id_check");
        assertThatThrownBy(() -> insertEntry(UUID.randomUUID(), "none"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("journal_entries_decision_id_check");
        assertThatThrownBy(() -> insertEntry(UUID.randomUUID(), "fallback"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("journal_entries_decision_id_check");
    }

    @Test
    void aResponseSavedBeforeV7ReplaysWithRiskCheckNone() {
        Account from = account(accounts, "CAD", true);
        Account to = account(accounts, "CAD", false);
        String key = key();
        UUID entryId = UUID.randomUUID();
        String hash = RequestHash.of("transfer",
                json.writeValueAsString(new TransferRequest(from.id(), to.id(), 300L, "CAD", null, null)));
        // The body a Phase 9 tally saved: every field of the entry as it was then, and no risk fields.
        String savedBody = """
                {"id":"%s","kind":"transfer","reversesId":null,"description":null,
                 "effectiveAt":"2026-10-28T12:00:00Z","createdAt":"2026-10-28T12:00:00Z",
                 "lines":[{"id":1,"accountId":"%s","amount":-300,"currency":"CAD"},
                          {"id":2,"accountId":"%s","amount":300,"currency":"CAD"}]}"""
                .formatted(entryId, from.id(), to.id());
        jdbc.sql("""
                insert into idempotency_keys (key, request_hash, response_status, response_body)
                values (:key, :hash, 201, cast(:body as jsonb))
                """)
                .param("key", key)
                .param("hash", hash)
                .param("body", savedBody)
                .update();

        JournalEntry replayed = transfer(key, from, to, 300).expectStatus().isCreated()
                .expectBody(JournalEntry.class).returnResult().getResponseBody();

        assertThat(replayed.id()).isEqualTo(entryId);
        assertThat(replayed.riskCheck()).isEqualTo("none");
        assertThat(replayed.decisionId()).isNull();
        assertThat(guard.received()).isEmpty();
    }

    /** The check off, as by default: the same fake on the URL is never asked. */
    @Nested
    @TestPropertySource(properties = "tally.risk.enabled=false")
    class CheckOff {

        @Test
        void aTransferIsPostedWithoutAskingTheGuard() {
            Account from = account(accounts, "CAD", true);
            Account to = account(accounts, "CAD", false);

            JournalEntry entry = transfer(key(), from, to, 600).expectStatus().isCreated()
                    .expectBody(JournalEntry.class).returnResult().getResponseBody();

            assertThat(guard.received()).isEmpty();
            assertThat(entry.riskCheck()).isEqualTo("none");
            assertThat(entry.decisionId()).isNull();
            assertThat(storedRisk(entry.id())).containsExactly(null, "none");
        }

    }

    private void assertRefusedAndAskedAgainOnRetry(String decision, String problemType) {
        UUID decisionId = UUID.randomUUID();
        guard.answerWith(decided(decisionId, decision, "hold"));
        Account from = account(accounts, "CAD", true);
        Account to = account(accounts, "CAD", false);
        String key = key();

        for (int attempt = 1; attempt <= 2; attempt++) {
            transfer(key, from, to, 700).expectStatus().isForbidden()
                    .expectBody()
                    .jsonPath("$.type").isEqualTo(problemType)
                    .jsonPath("$.status").isEqualTo(403)
                    .jsonPath("$.decisionId").isEqualTo(decisionId.toString());
            assertNothingPosted(key, from, to);
            assertThat(guard.callsFor(key)).as("guard calls after attempt %d", attempt).isEqualTo(attempt);
        }
    }

    private void assertBackupHoldWithinTheTimeout(FakeGuard.Answer answer) {
        guard.answerWith(answer);
        long started = System.nanoTime();
        assertBackupHold("timeout");
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(took).isLessThan(TIMEOUT.plus(MARGIN));
    }

    private void assertBackupHold(String outcome) {
        Account from = account(accounts, "CAD", true);
        Account to = account(accounts, "CAD", false);
        String key = key();
        long outcomesBefore = riskCalls(outcome);

        transfer(key, from, to, 500).expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.type").isEqualTo("/problems/risk-unavailable")
                .jsonPath("$.status").isEqualTo(503);

        assertNothingPosted(key, from, to);
        assertThat(riskCalls(outcome)).as("risk calls with outcome %s", outcome).isEqualTo(outcomesBefore + 1);
    }

    // A reply sets the remembered backup answer, so a test that changed it to allow puts hold back.
    private void restoreBackupHold(Account from, Account to) {
        guard.reset();
        transfer(key(), from, to, 1).expectStatus().isCreated();
    }

    private void assertNothingPosted(String key, Account from, Account to) {
        assertThat(lineCount(from)).isZero();
        assertThat(lineCount(to)).isZero();
        assertThat(balance(from)).isZero();
        assertThat(balance(to)).isZero();
        assertThat(outboxRowsFor(from)).isZero();
        assertThat(jdbc.sql("select count(*) from idempotency_keys where key = :key")
                .param("key", key).query(Long.class).single()).isZero();
    }

    // A client of its own, apart from tally's: its own timeout, call slots and connections, so no other test's
    // connections or waiting calls count.
    private RiskClient standaloneClient(Decision onTimeoutDefault) {
        return new RiskClient(new RiskProperties(true, guard.url(), STANDALONE_TIMEOUT, onTimeoutDefault),
                RestClient.builder(), json);
    }

    private static RiskResult ask(RiskClient risk) {
        return risk.decide(key(), UUID.randomUUID(), UUID.randomUUID(), 100, "CAD");
    }

    private RestTestClient.ResponseSpec transfer(String key, Account from, Account to, long amount) {
        return client.post().uri("/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fromAccountId", from.id(), "toAccountId", to.id(), "amount", amount,
                        "currency", "CAD"))
                .exchange();
    }

    private long riskCalls(String outcome) {
        Timer timer = meters.find(RISK_DURATION).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private List<String> storedRisk(UUID entryId) {
        return jdbc.sql("""
                select cast(decision_id as text) as decision_id, risk_check
                from journal_entries where id = :id
                """)
                .param("id", entryId)
                .query((rs, row) -> Arrays.asList(rs.getString("decision_id"), rs.getString("risk_check")))
                .single();
    }

    private List<String> outboxRisk(UUID entryId) {
        return jdbc.sql("""
                select payload ->> 'decisionId' as decision_id, payload ->> 'riskCheck' as risk_check
                from outbox where entry_id = :id
                """)
                .param("id", entryId)
                .query((rs, row) -> Arrays.asList(rs.getString("decision_id"), rs.getString("risk_check")))
                .single();
    }

    private long balance(Account account) {
        return jdbc.sql("select balance from balances where account_id = :id")
                .param("id", account.id()).query(Long.class).single();
    }

    private long lineCount(Account account) {
        return jdbc.sql("select count(*) from journal_lines where account_id = :id")
                .param("id", account.id()).query(Long.class).single();
    }

    private long outboxRowsFor(Account account) {
        return jdbc.sql("""
                select count(*) from outbox
                where payload -> 'lines' @> jsonb_build_array(jsonb_build_object('accountId', cast(:id as text)))
                """)
                .param("id", account.id()).query(Long.class).single();
    }

    private void insertEntry(UUID decisionId, String riskCheck) {
        jdbc.sql("""
                insert into journal_entries (id, kind, effective_at, decision_id, risk_check)
                values (:id, 'transfer', now(), :decisionId, :riskCheck)
                """)
                .param("id", UUID.randomUUID())
                .param("decisionId", decisionId)
                .param("riskCheck", riskCheck)
                .update();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static FakeGuard startGuard() {
        try {
            return FakeGuard.started();
        } catch (IOException e) {
            throw new IllegalStateException("The fake guard could not start", e);
        }
    }

}
