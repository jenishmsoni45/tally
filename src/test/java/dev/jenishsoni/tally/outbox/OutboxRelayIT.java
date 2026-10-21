package dev.jenishsoni.tally.outbox;

import static dev.jenishsoni.tally.LedgerFixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import dev.jenishsoni.tally.TestcontainersConfiguration;
import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.posting.JournalEntry;
import dev.jenishsoni.tally.posting.PostingResult;
import dev.jenishsoni.tally.posting.PostingService;
import dev.jenishsoni.tally.posting.TransferRequest;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every posted entry reaches a Kafka consumer once, within seconds, and with the same content the API
 * returned, even with two relays draining the outbox at the same time.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxRelayIT {

    private static final int TRANSFERS = 500;
    private static final Duration DELIVERY_DEADLINE = Duration.ofSeconds(10);
    private static final String CURRENCY = "CAD";

    @Autowired
    private PostingService posting;

    @Autowired
    private AccountService accounts;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ConsumerFactory<String, String> consumers;

    @Autowired
    private JsonMapper json;

    @Autowired
    private ObservationRegistry observations;

    @Test
    void fiveHundredTransfersReachTheConsumerOnceEachWithinTenSeconds() {
        try (EntryIdCollector collector = EntryIdCollector.fromNow(consumers)) {
            List<UUID> posted = postTransfers(TRANSFERS);
            long lastPost = System.nanoTime();

            collector.awaitIds(posted, DELIVERY_DEADLINE);
            Duration arrival = Duration.ofNanos(System.nanoTime() - lastPost);
            System.out.println("OutboxRelayIT arrived in " + arrival.toMillis() + " ms after the last post");
            awaitPublished(posted);
            collector.readToEnd(DELIVERY_DEADLINE);

            assertThat(collector.ids()).containsAll(posted);
            assertThat(posted).hasSize(TRANSFERS).doesNotHaveDuplicates();
            assertThat(arrival).isLessThanOrEqualTo(DELIVERY_DEADLINE);
            // Nothing fails in this test, so nothing may be sent twice. The dedupe is for failures (Phase 6).
            assertThat(collector.duplicatesIgnored()).isZero();
        }
    }

    @Test
    void twoRelaysRunningAtOnceStillDeliverEachEntryOnce() throws Exception {
        OutboxRelay second = new OutboxRelay(jdbc, kafka, transactions, observations);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger publishedBySecond = new AtomicInteger();

        try (EntryIdCollector collector = EntryIdCollector.fromNow(consumers)) {
            List<UUID> posted;
            Duration arrival;
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                // A tight loop keeps the second relay busy whenever rows exist, so every tick of the
                // scheduled relay meets it on the same rows.
                Future<?> loop = executor.submit(() -> {
                    while (!stop.get()) {
                        publishedBySecond.addAndGet(second.publishBatch());
                    }
                });
                try {
                    posted = postTransfers(TRANSFERS);
                    long lastPost = System.nanoTime();
                    collector.awaitIds(posted, DELIVERY_DEADLINE);
                    arrival = Duration.ofNanos(System.nanoTime() - lastPost);
                } finally {
                    // Also on failure, or closing the executor would wait for the loop forever.
                    stop.set(true);
                }
                loop.get(DELIVERY_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            }
            awaitPublished(posted);
            collector.readToEnd(DELIVERY_DEADLINE);
            System.out.println("OutboxRelayIT with two relays arrived in " + arrival.toMillis()
                    + " ms after the last post; the second relay published " + publishedBySecond + " rows");

            assertThat(collector.ids()).containsAll(posted);
            assertThat(arrival).isLessThanOrEqualTo(DELIVERY_DEADLINE);
            assertThat(publishedBySecond).as("rows published by the second relay").hasPositiveValue();
            // The point of the test: without row locking both relays would send the same rows.
            assertThat(collector.duplicatesIgnored()).isZero();
        }
    }

    @Test
    void theEventIsTheEntryAsSaved() {
        Account from = account(accounts, CURRENCY, true);
        Account to = account(accounts, CURRENCY, true);
        try (EntryIdCollector collector = EntryIdCollector.fromNow(consumers)) {
            PostingResult result = transferOneCent(from, to);
            UUID id = result.entry().id();

            collector.awaitIds(List.of(id), DELIVERY_DEADLINE);

            assertThat(collector.ids()).contains(id);
            // Records compare by value; comparing JSON trees would fail on IntNode against LongNode.
            assertThat(json.readValue(collector.payload(id), JournalEntry.class)).isEqualTo(result.entry());
        }
    }

    private List<UUID> postTransfers(int count) {
        // Both accounts may go negative, so no transfer is refused.
        Account from = account(accounts, CURRENCY, true);
        Account to = account(accounts, CURRENCY, true);
        List<UUID> posted = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            posted.add(transferOneCent(from, to).entry().id());
        }
        return posted;
    }

    private PostingResult transferOneCent(Account from, Account to) {
        return posting.postTransfer(UUID.randomUUID().toString(),
                new TransferRequest(from.id(), to.id(), 1L, CURRENCY, null, null));
    }

    private void awaitPublished(List<UUID> entryIds) {
        await("every outbox row published").atMost(DELIVERY_DEADLINE).until(() -> unpublished(entryIds) == 0);
    }

    private int unpublished(List<UUID> entryIds) {
        return jdbc.sql("select count(*) from outbox where entry_id in (:ids) and published_at is null")
                .param("ids", entryIds)
                .query(Integer.class)
                .single();
    }

}
