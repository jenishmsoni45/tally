package dev.jenishsoni.tally.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import dev.jenishsoni.tally.LedgerFixtures;
import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.outbox.EntryIdCollector;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the app with PostgreSQL and Kafka behind Toxiproxy, so a test can cut or slow either link while the app
 * is running. The containers start once for every failure test, also for a test that needs a Spring context of
 * its own. After each test the links are restored and the ledger is checked: total money per currency is
 * unchanged, every balance equals the sum of its lines, each idempotency key has at most one entry, and a Kafka
 * consumer saw every entry, keeping one copy of each because delivery is at least once.
 *
 * <p>The checks and the consumer connect straight to the containers, not through the proxies, so they see what
 * was committed and published whatever state the app's links are in. A test creates its accounts with
 * {@link #account} and posts on them only through {@link #transfer} and {@link #transferUntilPosted}, so the
 * checks know every key and every entry the test caused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // These tests run no telemetry backend, so exporting would only log failures to reach one.
        "management.otlp.metrics.export.enabled=false",
        "management.tracing.export.otlp.enabled=false" })
@AutoConfigureRestTestClient
abstract class FailureTest {

    // The first two of the ports ToxiproxyContainer exposes for proxies (8666 and up).
    private static final int DATABASE_PROXY_PORT = 8666;
    private static final int KAFKA_PROXY_PORT = 8667;

    // Throwaway password for the tally_app role in a container that is deleted after the run.
    private static final String APP_PASSWORD = "tally_app_test";

    // Far above the relay's 200 ms interval, and the time KafkaOutageIT allows a backlog to drain in.
    private static final Duration DELIVERY_DEADLINE = Duration.ofSeconds(30);

    // A short fixed pause is enough between attempts against a link that is down for seconds.
    private static final Duration RETRY_PAUSE = Duration.ofMillis(100);

    // Keys are global and outlive the test, so a reused database must not see the same ones twice.
    private static final String RUN = UUID.randomUUID().toString();

    private static final Network NETWORK = Network.newNetwork();

    private static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.13.0")).withNetwork(NETWORK);

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    // The app bootstraps through the proxy to a listener that advertises the proxy's address, so the connections
    // its clients open after bootstrap go through the proxy too. The test's consumer uses the default listener,
    // which the container maps straight to the host.
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
            .withNetwork(NETWORK)
            .withListener("kafka:19092", FailureTest::kafkaProxyAddress);

    /** The app's connection to PostgreSQL. */
    static final Link DATABASE_LINK;

    /** The app's connections to Kafka. */
    static final Link KAFKA_LINK;

    // Once per test JVM, shared by every subclass; Ryuk removes the containers when the JVM exits. Toxiproxy
    // starts first because Kafka's advertised listener needs its mapped port.
    static {
        TOXIPROXY.start();
        POSTGRES.start();
        KAFKA.start();
        ToxiproxyClient toxiproxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
        DATABASE_LINK = Link.create(toxiproxy, "postgres", DATABASE_PROXY_PORT, "postgres:5432");
        KAFKA_LINK = Link.create(toxiproxy, "kafka", KAFKA_PROXY_PORT, "kafka:19092");
    }

    /** Straight to the container as its owner, for checks that must not depend on the app's link. */
    final JdbcClient database = JdbcClient.create(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

    @Autowired
    private RestTestClient client;

    @Autowired
    private AccountService accounts;

    @Autowired
    private JsonMapper json;

    private final List<UUID> testAccounts = new CopyOnWriteArrayList<>();
    private final Set<String> keysSent = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<UUID>> answeredEntries = new ConcurrentHashMap<>();
    private final Queue<Answer> failedAnswers = new ConcurrentLinkedQueue<>();
    private Map<String, Long> totalsBefore;
    private EntryIdCollector consumer;

    @DynamicPropertySource
    static void connectThroughTheProxies(DynamicPropertyRegistry registry) {
        // Flyway reuses the datasource URL, so the migrations go through the proxy too, before any toxic exists.
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + TOXIPROXY.getHost() + ":"
                + TOXIPROXY.getMappedPort(DATABASE_PROXY_PORT) + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.datasource.username", () -> "tally_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.appPassword", () -> APP_PASSWORD);
        registry.add("spring.kafka.bootstrap-servers", FailureTest::kafkaProxyAddress);
    }

    @BeforeEach
    void recordTotalsAndStartConsumer() {
        totalsBefore = totalsPerCurrency();
        consumer = EntryIdCollector.fromNow(directConsumers());
    }

    @AfterEach
    void restoreLinksAndCheckLedger(TestInfo test) throws IOException {
        try {
            DATABASE_LINK.restore();
            KAFKA_LINK.restore();
            Set<UUID> entries = entriesOnTestAccounts();
            consumer.awaitIds(entries, DELIVERY_DEADLINE);
            System.out.println(getClass().getSimpleName() + "." + test.getTestMethod().orElseThrow().getName()
                    + ": keys=" + keysSent.size() + " entries=" + entries.size()
                    + " failedAttempts=" + failedAnswers.size() + " duplicatesIgnored=" + consumer.duplicatesIgnored());

            assertThat(totalsPerCurrency()).as("total money per currency").isEqualTo(totalsBefore);
            assertThat(driftedAccounts()).as("accounts whose balance differs from the sum of their lines").isEmpty();
            assertOneEntryPerKey(entries);
            assertThat(consumer.ids()).as("entries the consumer saw").containsAll(entries);
        } finally {
            consumer.close();
        }
    }

    /** An account the checks after the test cover. Post on it only through this class. */
    Account account(String currency, boolean allowNegative) {
        Account account = LedgerFixtures.account(accounts, currency, allowNegative);
        testAccounts.add(account.id());
        return account;
    }

    /** One POST /transfers, answered as the server answered it; the answer is recorded for the checks. */
    Answer transfer(String key, Map<String, Object> body) {
        keysSent.add(key);
        EntityExchangeResult<String> result = client.post().uri("/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .returnResult(String.class);
        Answer answer = new Answer(result.getStatus().value(), result.getResponseHeaders().getContentType(),
                result.getResponseBody());
        if (answer.status() == 201) {
            answeredEntries.computeIfAbsent(key, _ -> ConcurrentHashMap.newKeySet()).add(entryId(answer));
        } else if (answer.status() >= 500) {
            failedAnswers.add(answer);
        }
        return answer;
    }

    /**
     * Sends the transfer until it is answered 201, with the same key and body every time, as a client that cannot
     * tell whether a failed attempt moved money must. Only 5xx answers are retried; any other answer is returned
     * as it is, and so is the last failure once the deadline has passed.
     */
    Answer transferUntilPosted(String key, Map<String, Object> body, Duration deadline) throws InterruptedException {
        long end = System.nanoTime() + deadline.toNanos();
        Answer answer = transfer(key, body);
        while (answer.status() >= 500 && System.nanoTime() - end < 0) {
            Thread.sleep(RETRY_PAUSE);
            answer = transfer(key, body);
        }
        return answer;
    }

    /** Attempts answered with a 5xx so far, which shows that a fault reached the app. */
    int failedAttempts() {
        return failedAnswers.size();
    }

    /** Every attempt answered with a 5xx so far. */
    List<Answer> failedAnswers() {
        return List.copyOf(failedAnswers);
    }

    static Map<String, Object> transferBody(Account from, Account to, long amount) {
        return Map.of("fromAccountId", from.id(), "toAccountId", to.id(), "amount", amount,
                "currency", from.currency());
    }

    static String key(String name) {
        return "failure-" + RUN + "-" + name;
    }

    private UUID entryId(Answer answer) {
        return UUID.fromString(json.readTree(answer.body()).path("id").asString());
    }

    private static String kafkaProxyAddress() {
        return TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(KAFKA_PROXY_PORT);
    }

    private static ConsumerFactory<String, String> directConsumers() {
        return new DefaultKafkaConsumerFactory<>(
                Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringDeserializer(), new StringDeserializer());
    }

    // Every entry sums to zero per currency, so in a sound ledger every total is zero; only the currencies whose
    // total is not are listed, and a new account or currency with no money does not count as a change.
    private Map<String, Long> totalsPerCurrency() {
        return database.sql("""
                select a.currency, sum(b.balance) as total
                from balances b
                join accounts a on a.id = b.account_id
                group by a.currency
                having sum(b.balance) <> 0
                """)
                .query((rs, row) -> Map.entry(rs.getString("currency"), rs.getLong("total")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
    }

    // Its own query rather than the reconciliation job, so the check does not depend on the code it checks.
    private List<UUID> driftedAccounts() {
        return database.sql("""
                select b.account_id
                from balances b
                left join journal_lines l on l.account_id = b.account_id
                group by b.account_id, b.balance
                having b.balance <> coalesce(sum(l.amount), 0)
                """)
                .query(UUID.class)
                .list();
    }

    private Set<UUID> entriesOnTestAccounts() {
        if (testAccounts.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(database.sql("select distinct entry_id from journal_lines where account_id in (:accounts)")
                .param("accounts", testAccounts)
                .query(UUID.class)
                .list());
    }

    private Map<String, UUID> savedEntryPerKey() {
        if (keysSent.isEmpty()) {
            return Map.of();
        }
        return database.sql("select key, response_body ->> 'id' as entry_id from idempotency_keys where key in (:keys)")
                .param("keys", keysSent)
                .query((rs, row) -> Map.entry(rs.getString("key"), UUID.fromString(rs.getString("entry_id"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Every 201 for a key carries the same entry, the key saved that entry, no key saved an entry its client was
     * never told about, and the test's accounts hold exactly the saved entries, one per key.
     */
    private void assertOneEntryPerKey(Set<UUID> entries) {
        answeredEntries.forEach((key, ids) -> assertThat(ids).as("entries answered for key %s", key).hasSize(1));
        Map<String, UUID> answered = answeredEntries.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().iterator().next()));
        Map<String, UUID> saved = savedEntryPerKey();

        assertThat(saved).as("entry saved per key").isEqualTo(answered);
        assertThat(Set.copyOf(saved.values())).as("entries on the test's accounts")
                .hasSize(saved.size())
                .isEqualTo(entries);
    }

    /** The status, content type and body of one answer. */
    record Answer(int status, MediaType contentType, String body) {

        /** Whether the body is an RFC 9457 problem, as every error response must be. */
        boolean isProblem() {
            return contentType != null && contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }

    }

    /** One proxied connection from the app to a container, which a test can cut or slow and then restore. */
    static final class Link {

        private final Proxy proxy;

        private Link(Proxy proxy) {
            this.proxy = proxy;
        }

        private static Link create(ToxiproxyClient toxiproxy, String name, int listenPort, String upstream) {
            try {
                return new Link(toxiproxy.createProxy(name, "0.0.0.0:" + listenPort, upstream));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create the " + name + " proxy", e);
            }
        }

        /**
         * Drops every byte both ways, as a network cut does, while the app's connections stay open. A timeout of 0
         * is Toxiproxy's documented way to do that; a bandwidth of 0 no longer is, as 2.13.0 reads it as no limit.
         */
        void cut() throws IOException {
            proxy.toxics().timeout("cut-upstream", ToxicDirection.UPSTREAM, 0);
            proxy.toxics().timeout("cut-downstream", ToxicDirection.DOWNSTREAM, 0);
        }

        /** Holds back every reply from the container by latency, so each round trip takes at least that long. */
        void slow(Duration latency) throws IOException {
            proxy.toxics().latency("latency-downstream", ToxicDirection.DOWNSTREAM, latency.toMillis());
        }

        /**
         * Removes every toxic. Removing a cut also closes the connections that were open during it, so the app
         * finds them broken and has to reconnect.
         */
        void restore() throws IOException {
            for (Toxic toxic : proxy.toxics().getAll()) {
                toxic.remove();
            }
        }

    }

}
