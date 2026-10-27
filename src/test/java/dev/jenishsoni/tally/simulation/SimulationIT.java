package dev.jenishsoni.tally.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import dev.jenishsoni.tally.TestcontainersConfiguration;
import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.posting.JournalEntry;
import dev.jenishsoni.tally.reconcile.AccountDrift;
import dev.jenishsoni.tally.reconcile.ReconciliationReport;
import dev.jenishsoni.tally.simulation.LedgerModel.ModelAccount;
import dev.jenishsoni.tally.simulation.LedgerModel.ModelEntry;
import dev.jenishsoni.tally.simulation.LedgerModel.Outcome;
import tools.jackson.databind.json.JsonMapper;

/**
 * For each seed, 500 operations from a weighted generator go to the HTTP API and to an in-memory model of the
 * ledger's rules. After every operation the API's status, the entry it answered with and every balance of the run
 * must match the model, and at the end of the run the ledger must hold its invariants. Seeds 1 to N run (-Dseeds=N,
 * default 20); a failure prints the seed, the step and the last 10 operations, and -Dseed=X replays that seed alone.
 * Each seed works on its own accounts and keys, and every check looks only at those, so seeds cannot affect each
 * other.
 */
@Tag("simulation")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class SimulationIT {

    private static final int OPERATIONS = 500;
    private static final int DEFAULT_SEEDS = 20;

    // Enough to show how a run reached the failing step without burying it.
    private static final int SHOWN_ON_FAILURE = 10;

    private static final int CREATED = 201;

    // Keys and account codes are global and outlive the test, so a reused database must not see the same ones
    // twice. They do not change what a run does, so a seed still replays the same operations. Eight hex digits keep
    // an account code within the API's 64 characters for any long seed.
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    // The nil UUID: account and entry ids are random (version 4) UUIDs, so none can ever be this one.
    private static final UUID NO_SUCH_ID = new UUID(0, 0);

    // The seeds' own time, without the containers and the Spring context starting before the first one.
    private static final AtomicLong operationsSent = new AtomicLong();
    private static final AtomicLong nanosPlaying = new AtomicLong();

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    static LongStream seeds() {
        String seed = System.getProperty("seed");
        if (seed != null) {
            return LongStream.of(Long.parseLong(seed));
        }
        return LongStream.rangeClosed(1, Integer.getInteger("seeds", DEFAULT_SEEDS));
    }

    @AfterAll
    static void printTotal() {
        System.out.println("SimulationIT operations=" + operationsSent + " in "
                + Duration.ofNanos(nanosPlaying.get()).toMillis() + " ms");
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void theApiAndTheModelAgreeOnEveryOperation(long seed) {
        new Run(seed).play();
    }

    /** One seed's run: its model, its generator, and the id the server gave each account and entry of the model. */
    private final class Run {

        private final long seed;
        private final LedgerModel model = new LedgerModel();
        private final OperationGenerator generator;
        private final List<UUID> accountIds = new ArrayList<>();
        private final List<UUID> entryIds = new ArrayList<>();
        private final List<String> entryBodies = new ArrayList<>();
        private final List<Request> requests = new ArrayList<>();
        private final Deque<Step> recent = new ArrayDeque<>();
        private final Map<Integer, Integer> statuses = new TreeMap<>();
        private int replays;
        private int step;
        // The operation of the step being played, or null while the generator is still choosing it.
        private Operation current;

        Run(long seed) {
            this.seed = seed;
            this.generator = new OperationGenerator(seed);
        }

        void play() {
            long start = System.nanoTime();
            try {
                for (step = 0; step < OPERATIONS; step++) {
                    playStep();
                }
                checkInvariants();
            } catch (AssertionError | RuntimeException e) {
                // In the failure itself, so the build's output shows it without the test's console output.
                throw new AssertionError(failureReport(), e);
            }
            long elapsed = System.nanoTime() - start;
            operationsSent.addAndGet(OPERATIONS);
            nanosPlaying.addAndGet(elapsed);
            System.out.println("SimulationIT seed=" + seed + " operations=" + OPERATIONS + " accounts="
                    + accountIds.size() + " entries=" + entryIds.size() + " replays=" + replays + " statuses="
                    + statuses + " ms=" + Duration.ofNanos(elapsed).toMillis());
        }

        private void playStep() {
            // Cleared first, so a failure while choosing the operation does not name the previous step's.
            current = null;
            Operation operation = generator.next(model);
            current = operation;
            // A retry sends the earlier request unchanged and asks the model the same question again; a reused key
            // sends a new request under the earlier request's key.
            Request request = switch (operation) {
                case Operation.Retry retry -> requests.get(retry.operation());
                case Operation.ReuseKey reuse -> request(reuse.request(), requests.get(reuse.operation()).key());
                default -> request(operation, "sim-" + RUN + "-" + seed + "-" + step);
            };
            requests.add(request);
            Outcome expected = request.expect().get();
            Answer answer = send(request);
            remember(new Step(step, operation, request.operation(), expected, answer.status()));
            statuses.merge(answer.status(), 1, Integer::sum);
            checkAnswer(request, expected, answer);
            checkBalances();
        }

        // A step's own name is unique in the test run, so it serves as an account code or an idempotency key.
        private Request request(Operation operation, String name) {
            return switch (operation) {
                case Operation.OpenAccount open -> new Request(operation, "/accounts", null,
                        Map.of("code", name, "currency", open.currency(), "allowNegative", open.allowNegative()),
                        () -> model.openAccount(name, open.currency(), open.allowNegative()));
                case Operation.Transfer transfer -> new Request(operation, "/transfers", name,
                        Map.of("fromAccountId", accountId(transfer.from()), "toAccountId", accountId(transfer.to()),
                                "amount", transfer.amount(), "currency", transfer.currency()),
                        () -> model.transfer(name, transfer));
                case Operation.Entry entry -> new Request(operation, "/entries", name,
                        Map.of("lines", entry.lines().stream().map(this::lineBody).toList()),
                        () -> model.entry(name, entry));
                case Operation.Reverse reverse -> new Request(operation,
                        "/entries/" + entryId(reverse.entry()) + "/reverse", name, null,
                        () -> model.reverse(name, reverse));
                case Operation.Retry _, Operation.ReuseKey _ ->
                    throw new IllegalArgumentException("A retry or a reused key sends another step's request");
            };
        }

        private Map<String, Object> lineBody(Operation.Line line) {
            return Map.of("accountId", accountId(line.account()), "amount", line.amount(), "currency", line.currency());
        }

        private UUID accountId(int index) {
            return index == Operation.UNKNOWN ? NO_SUCH_ID : accountIds.get(index);
        }

        private UUID entryId(int index) {
            return index == Operation.UNKNOWN ? NO_SUCH_ID : entryIds.get(index);
        }

        private Answer send(Request request) {
            RestTestClient.RequestBodySpec spec = client.post().uri(request.path());
            if (request.key() != null) {
                spec = spec.header("Idempotency-Key", request.key());
            }
            RestTestClient.RequestHeadersSpec<?> ready = request.body() == null
                    ? spec
                    : spec.contentType(MediaType.APPLICATION_JSON).body(request.body());
            EntityExchangeResult<String> result = ready.exchange().returnResult(String.class);
            return new Answer(result.getStatus().value(), result.getResponseBody());
        }

        private void remember(Step played) {
            recent.addLast(played);
            if (recent.size() > SHOWN_ON_FAILURE) {
                recent.removeFirst();
            }
        }

        private void checkAnswer(Request request, Outcome expected, Answer answer) {
            assertThat(answer.status()).as("status of %s, answered %s", request.path(), answer.body())
                    .isEqualTo(expected.status());
            if (expected.status() != CREATED) {
                return;
            }
            if (request.operation() instanceof Operation.OpenAccount) {
                recordAccount(expected, answer);
            } else {
                checkEntry(expected, answer);
            }
        }

        private void recordAccount(Outcome expected, Answer answer) {
            assertThat(expected.index()).as("model index of the new account").isEqualTo(accountIds.size());
            accountIds.add(json.readValue(answer.body(), Account.class).id());
        }

        // A replay must be the first answer exactly; a new entry must have the lines and links the model expects.
        private void checkEntry(Outcome expected, Answer answer) {
            if (expected.replayed()) {
                replays++;
                assertThat(answer.body()).as("replay of entry %d", expected.index())
                        .isEqualTo(entryBodies.get(expected.index()));
                return;
            }
            assertThat(expected.index()).as("model index of the new entry").isEqualTo(entryIds.size());
            JournalEntry entry = json.readValue(answer.body(), JournalEntry.class);
            entryIds.add(entry.id());
            entryBodies.add(answer.body());
            ModelEntry modelled = model.entries().get(expected.index());
            assertThat(entry.kind()).as("kind").isEqualTo(modelled.kind());
            assertThat(entry.reversesId()).as("reversed entry")
                    .isEqualTo(modelled.reverses() == Outcome.NONE ? null : entryIds.get(modelled.reverses()));
            assertThat(entry.lines().stream().map(line -> new Line(line.accountId(), line.amount(), line.currency())))
                    .as("lines")
                    .containsExactlyElementsOf(modelled.lines().stream()
                            .map(line -> new Line(accountIds.get(line.account()), line.amount(), line.currency()))
                            .toList());
        }

        // Every account of the run after every step, so a change to an account the step did not name shows at once.
        private void checkBalances() {
            if (accountIds.isEmpty()) {
                return;
            }
            List<ModelAccount> modelled = model.accounts();
            Map<UUID, Long> expected = IntStream.range(0, accountIds.size()).boxed()
                    .collect(Collectors.toMap(accountIds::get, index -> modelled.get(index).balance()));
            Map<UUID, Long> stored = jdbc.sql("select account_id, balance from balances where account_id in (:ids)")
                    .param("ids", accountIds)
                    .query((rs, row) -> Map.entry(rs.getObject("account_id", UUID.class), rs.getLong("balance")))
                    .list()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(stored).as("balances").isEqualTo(expected);
        }

        private void checkInvariants() {
            assertThat(model.totalsPerCurrency()).as("money per currency in the model")
                    .allSatisfy((currency, total) -> assertThat(total).as(currency).isZero());
            assertThat(totalsPerCurrency()).as("money per currency in the ledger")
                    .allSatisfy((currency, total) -> assertThat(total).as(currency).isZero());
            assertThat(driftedAccounts()).as("accounts whose balance differs from the sum of their lines").isEmpty();
            assertThat(entriesOnRunAccounts()).as("entries on the run's accounts")
                    .hasSize(model.entries().size())
                    .isEqualTo(Set.copyOf(entryIds));
            assertThat(savedKeys()).as("saved keys, and the distinct entries they saved")
                    .containsExactly((long) entryIds.size(), (long) entryIds.size());
            // The job recounts the whole database, so only what it finds on this run's accounts is this run's.
            assertThat(recount().drift()).extracting(AccountDrift::accountId)
                    .as("this run's accounts that the reconciliation job found drifted")
                    .doesNotContainAnyElementsOf(accountIds);
        }

        private Map<String, Long> totalsPerCurrency() {
            return jdbc.sql("""
                    select a.currency, sum(b.balance) as total
                    from balances b
                    join accounts a on a.id = b.account_id
                    where b.account_id in (:ids)
                    group by a.currency
                    """)
                    .param("ids", accountIds)
                    .query((rs, row) -> Map.entry(rs.getString("currency"), rs.getLong("total")))
                    .list()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        private List<UUID> driftedAccounts() {
            return jdbc.sql("""
                    select b.account_id
                    from balances b
                    left join journal_lines l on l.account_id = b.account_id
                    where b.account_id in (:ids)
                    group by b.account_id, b.balance
                    having b.balance <> coalesce(sum(l.amount), 0)
                    """)
                    .param("ids", accountIds)
                    .query(UUID.class)
                    .list();
        }

        private Set<UUID> entriesOnRunAccounts() {
            return Set.copyOf(jdbc.sql("select distinct entry_id from journal_lines where account_id in (:ids)")
                    .param("ids", accountIds)
                    .query(UUID.class)
                    .list());
        }

        // One row per successful posting, each saving a different entry, shows that no key holds two entries.
        private List<Long> savedKeys() {
            return jdbc.sql("""
                    select count(*) as saved, count(distinct response_body ->> 'id') as entries
                    from idempotency_keys
                    where starts_with(key, :prefix)
                    """)
                    .param("prefix", "sim-" + RUN + "-" + seed + "-")
                    .query((rs, row) -> List.of(rs.getLong("saved"), rs.getLong("entries")))
                    .single();
        }

        private ReconciliationReport recount() {
            return client.post().uri("/admin/reconcile")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestcontainersConfiguration.ADMIN_API_KEY)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(ReconciliationReport.class)
                    .returnResult()
                    .getResponseBody();
        }

        private String failureReport() {
            StringBuilder report = new StringBuilder("Simulation failed: seed=" + seed + " " + where()
                    + "\nReplay it with ./gradlew simulationTest -Dseed=" + seed + "\nLast " + recent.size()
                    + " steps answered:");
            recent.forEach(played -> report.append("\n  ").append(played));
            return report.toString();
        }

        // The loop leaves step at OPERATIONS only once every step has passed.
        private String where() {
            if (step == OPERATIONS) {
                return "in the end-of-run checks after all " + OPERATIONS + " steps";
            }
            String at = "at step " + step + " of " + OPERATIONS;
            return current == null ? at + ", while choosing its operation" : at + ": " + current;
        }

    }

    /** A request as sent, kept so a retry sends it again unchanged; expect asks the model what it must answer. */
    private record Request(Operation operation, String path, String key, Object body, Supplier<Outcome> expect) {
    }

    private record Answer(int status, String body) {
    }

    /** One step as played: sent is the operation whose request went out, which for a retry is the earlier one. */
    private record Step(int index, Operation operation, Operation sent, Outcome expected, int answered) {

        @Override
        public String toString() {
            String played = operation instanceof Operation.Retry
                    ? operation + ", resending " + sent
                    : operation.toString();
            return "step " + index + ": " + played + " expected " + expected + ", answered " + answered;
        }

    }

    private record Line(UUID accountId, long amount, String currency) {
    }

}
