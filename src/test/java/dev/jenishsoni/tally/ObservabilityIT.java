package dev.jenishsoni.tally;

import static dev.jenishsoni.tally.LedgerFixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.posting.JournalEntry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every posting is counted, timed and logged with its entry id and idempotency key, every publish is
 * logged with its entry id, and the outbox gauges show rows the relay has not published. The meter
 * registry is shared with every other test in the cached context, so each test compares before and after.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT {

    private static final String CURRENCY = "CAD";

    // The relay runs every 200 ms, so this is far above normal and still fails fast if it stops.
    private static final Duration PUBLISH_DEADLINE = Duration.ofSeconds(10);

    @Autowired
    private RestTestClient client;

    @Autowired
    private AccountService accounts;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JsonMapper json;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void aTransferIsCountedTimedAndLoggedWithItsEntryIdAndKey(CapturedOutput output) {
        Map<String, Object> body = transferBody(true);
        String key = key();
        double postings = postings("transfer");
        long posted = postingDurations("transfer", "posted");
        long lockWaits = lockWaits();

        UUID id = transfer(key, body);

        assertThat(postings("transfer") - postings).isEqualTo(1);
        assertThat(postingDurations("transfer", "posted") - posted).isOne();
        assertThat(lockWaits() - lockWaits).as("one lock statement per posting").isOne();
        JsonNode line = logLine(output, "Posted entry " + id).orElseThrow();
        assertThat(line.path("entry_id").asString()).isEqualTo(id.toString());
        assertThat(line.path("idempotency_key").asString()).isEqualTo(key);
        assertThat(line.path("traceId").asString()).as("the request's trace").hasSize(32);
        assertThat(line.path("spanId").asString()).hasSize(16);
    }

    @Test
    void aReplayIsTimedAndLoggedButNotCountedAsAPosting(CapturedOutput output) {
        Map<String, Object> body = transferBody(true);
        String key = key();
        double postings = postings("transfer");
        long replayed = postingDurations("transfer", "replayed");

        UUID first = transfer(key, body);
        UUID second = transfer(key, body);

        assertThat(second).isEqualTo(first);
        assertThat(postings("transfer") - postings).isEqualTo(1);
        assertThat(postingDurations("transfer", "replayed") - replayed).isOne();
        JsonNode line = logLine(output, "Replayed entry " + first).orElseThrow();
        assertThat(line.path("entry_id").asString()).isEqualTo(first.toString());
        assertThat(line.path("idempotency_key").asString()).isEqualTo(key);
    }

    @Test
    void aTransferRefusedForInsufficientFundsIsTimedAsRejected() {
        Map<String, Object> body = transferBody(false);
        double postings = postings("transfer");
        long rejected = postingDurations("transfer", "rejected");

        client.post().uri("/transfers")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isEqualTo(422);

        assertThat(postingDurations("transfer", "rejected") - rejected).isOne();
        assertThat(postings("transfer")).isEqualTo(postings);
    }

    @Test
    void aPostingThatFailsIsLoggedWithItsKeyAndTimedAsFailed(CapturedOutput output) {
        Map<String, Object> body = transferBody(true);
        String key = key();
        long failed = postingDurations("transfer", "failed");
        JdbcClient owner = JdbcClient.create(ownerDataSource());

        // Without this privilege the posting's outbox insert fails with a database error, not a refusal, and the
        // whole posting rolls back.
        owner.sql("revoke insert on outbox from tally_app").update();
        try {
            client.post().uri("/transfers")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange()
                    .expectStatus().is5xxServerError();
        } finally {
            owner.sql("grant insert on outbox to tally_app").update();
        }

        assertThat(postingDurations("transfer", "failed") - failed).isOne();
        JsonNode line = logLine(output, "Posting transfer failed").orElseThrow();
        assertThat(line.path("idempotency_key").asString()).isEqualTo(key);
    }

    @Test
    void aBatchKafkaRefusesIsLoggedWithItsEntryIds(CapturedOutput output) {
        JdbcClient owner = JdbcClient.create(ownerDataSource());
        UUID entryId = UUID.randomUUID();

        // Over the producer's 1 MB max.request.size, so Kafka refuses the record after the relay has read it, and
        // its batch fails on every run until the row is gone.
        owner.sql("""
                insert into outbox (entry_id, type, payload)
                values (:entryId, 'entry.posted', jsonb_build_object('padding', repeat('x', 1100000)))
                """)
                .param("entryId", entryId)
                .update();
        try {
            await("the relay's failure line naming the entry").atMost(PUBLISH_DEADLINE)
                    .until(() -> logLine(output, "Publishing entries ")
                            .filter(line -> line.path("message").asString().contains(entryId.toString()))
                            .isPresent());
        } finally {
            owner.sql("delete from outbox where entry_id = :entryId").param("entryId", entryId).update();
        }
    }

    @Test
    void theRelayLogsThePublishWithTheEntryId(CapturedOutput output) {
        Map<String, Object> body = transferBody(true);

        UUID id = transfer(key(), body);

        await("the relay's log line for the entry").atMost(PUBLISH_DEADLINE)
                .until(() -> logLine(output, "Published entry " + id).isPresent());
        JsonNode line = logLine(output, "Published entry " + id).orElseThrow();
        assertThat(line.path("entry_id").asString()).isEqualTo(id.toString());
    }

    @Test
    void theOutboxGaugesShowARowTheRelayHasNotPublished() {
        DriverManagerDataSource ownerDataSource = ownerDataSource();
        JdbcClient owner = JdbcClient.create(ownerDataSource);
        TransactionTemplate ownerTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(ownerDataSource));
        UUID entryId = UUID.randomUUID();

        // The relay's SELECT ... FOR UPDATE needs this privilege, so without it every relay run fails and
        // rolls back, as a stuck relay would, and the row below stays unpublished. Locking the row instead
        // would leave a moment between its insert committing and the lock in which the relay, running every
        // 200 ms, could publish it.
        owner.sql("revoke update (published_at) on outbox from tally_app").update();
        try {
            owner.sql("""
                    insert into outbox (entry_id, type, payload, created_at)
                    values (:entryId, 'entry.posted', '{}', now() - interval '1 minute')
                    """)
                    .param("entryId", entryId)
                    .update();

            assertThat(gauge("ledger.outbox.pending")).isGreaterThanOrEqualTo(1);
            assertThat(gauge("ledger.outbox.oldest.age.seconds")).isGreaterThanOrEqualTo(60);
        } finally {
            // One transaction, so the relay gets the privilege back only once the row is gone and never
            // publishes this made-up event.
            ownerTransaction.executeWithoutResult(_ -> {
                owner.sql("delete from outbox where entry_id = :entryId").param("entryId", entryId).update();
                owner.sql("grant update (published_at) on outbox to tally_app").update();
            });
        }
    }

    private UUID transfer(String key, Map<String, Object> body) {
        JournalEntry entry = client.post().uri("/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(JournalEntry.class)
                .returnResult()
                .getResponseBody();
        assertThat(entry).isNotNull();
        return entry.id();
    }

    // The schema owner, which can change tally_app's privileges and write rows the application cannot.
    private DriverManagerDataSource ownerDataSource() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    // Each console line is one ECS JSON object; parsing it checks the fields, not just that the text appears.
    private Optional<JsonNode> logLine(CapturedOutput output, String messagePrefix) {
        return output.getOut().lines()
                .filter(line -> line.startsWith("{"))
                .map(json::readTree)
                .filter(line -> line.path("message").asString().startsWith(messagePrefix))
                .findFirst();
    }

    // Tagged meters are registered on first use, so one this context has not used yet counts as zero.
    private double postings(String kind) {
        Counter counter = registry.find("ledger.postings").tag("kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }

    private long postingDurations(String operation, String outcome) {
        Timer timer = registry.find("ledger.posting.duration")
                .tags("operation", operation, "outcome", outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private long lockWaits() {
        return registry.get("ledger.lock.wait").timer().count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    // A source that may not go negative starts at 0, so a transfer from it is refused for insufficient funds.
    private Map<String, Object> transferBody(boolean sourceMayGoNegative) {
        Account from = account(accounts, CURRENCY, sourceMayGoNegative);
        Account to = account(accounts, CURRENCY, true);
        return Map.of("fromAccountId", from.id(), "toAccountId", to.id(), "amount", 100, "currency", CURRENCY);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

}
