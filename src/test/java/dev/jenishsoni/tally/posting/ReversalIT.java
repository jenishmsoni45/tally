package dev.jenishsoni.tally.posting;

import static dev.jenishsoni.tally.LedgerFixtures.account;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

import dev.jenishsoni.tally.TestcontainersConfiguration;
import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class ReversalIT {

    private static final int CONCURRENT_REVERSALS = 8;

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccountService accounts;

    @Autowired
    private PostingService posting;

    @Test
    void aReversalPostsTheMirrorLinesAndRestoresTheBalances() {
        Account source = account(accounts, "CAD", true);
        Account destination = account(accounts, "CAD", false);
        JournalEntry original = transfer(source, destination, 2500);

        client.post().uri("/entries/{id}/reverse", original.id())
                .header("Idempotency-Key", key())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("reversal")
                .jsonPath("$.reversesId").isEqualTo(original.id().toString())
                .jsonPath("$.lines.length()").isEqualTo(2)
                .jsonPath("$.lines[0].accountId").isEqualTo(source.id().toString())
                .jsonPath("$.lines[0].amount").isEqualTo(2500)
                .jsonPath("$.lines[1].accountId").isEqualTo(destination.id().toString())
                .jsonPath("$.lines[1].amount").isEqualTo(-2500);

        assertThat(balance(source)).isZero();
        assertThat(balance(destination)).isZero();
        assertThat(lineCount(original.id())).as("the original entry is untouched").isEqualTo(2);
        assertThat(lineCount(source)).isEqualTo(2);
    }

    @Test
    void reversingTheSameEntryAgainWithAnotherKeyIs409() {
        Account source = account(accounts, "CAD", true);
        Account destination = account(accounts, "CAD", true);
        JournalEntry original = transfer(source, destination, 2500);
        reverse(original.id(), key(), 201);

        reverse(original.id(), key(), 409);

        assertThat(reversalCount(original.id())).isOne();
        assertThat(balance(destination)).isZero();
    }

    @Test
    void aSecondReversalIs409EvenWhenItWouldAlsoOverdrawAnAccount() {
        Account treasury = account(accounts, "CAD", true);
        Account holder = account(accounts, "CAD", false);
        JournalEntry funding = transfer(treasury, holder, 100);
        reverse(funding.id(), key(), 201);

        reverse(funding.id(), key(), 409);

        assertThat(balance(holder)).isZero();
    }

    @Test
    void reversingWithTheSameKeyTwiceReplaysTheFirstReversal() {
        Account source = account(accounts, "CAD", true);
        Account destination = account(accounts, "CAD", true);
        JournalEntry original = transfer(source, destination, 2500);
        String key = key();

        String first = reverse(original.id(), key, 201);
        String second = reverse(original.id(), key, 201);

        assertThat(second).isEqualTo(first);
        assertThat(reversalCount(original.id())).isOne();
        assertThat(balance(destination)).isZero();
    }

    @Test
    void theSameKeyForAnotherEntryIs409() {
        Account source = account(accounts, "CAD", true);
        Account destination = account(accounts, "CAD", true);
        JournalEntry first = transfer(source, destination, 100);
        JournalEntry second = transfer(source, destination, 200);
        String key = key();
        reverse(first.id(), key, 201);

        reverse(second.id(), key, 409);

        assertThat(reversalCount(second.id())).isZero();
    }

    @Test
    void aReversalThatWouldOverdrawAnAccountIs422AndKeepsItsKeyFree() {
        Account treasury = account(accounts, "CAD", true);
        Account holder = account(accounts, "CAD", false);
        Account shop = account(accounts, "CAD", false);
        JournalEntry funding = transfer(treasury, holder, 100);
        JournalEntry spending = transfer(holder, shop, 100);
        String key = key();

        reverse(funding.id(), key, 422);
        reverse(spending.id(), key(), 201);
        reverse(funding.id(), key, 201);

        assertThat(balance(holder)).isZero();
        assertThat(balance(treasury)).isZero();
    }

    @Test
    void reversingAnUnknownEntryIs404() {
        reverse(UUID.randomUUID(), key(), 404);
    }

    @Test
    void anEntryReversedByEightRequestsAtOnceIsReversedOnceAndTheRestAreConflicts() throws Exception {
        Account treasury = account(accounts, "CAD", true);
        Account holder = account(accounts, "CAD", false);
        JournalEntry funding = transfer(treasury, holder, 100);
        CountDownLatch go = new CountDownLatch(1);

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REVERSALS)) {
            List<Future<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REVERSALS; i++) {
                requests.add(pool.submit(() -> {
                    go.await();
                    return reverseStatus(funding.id());
                }));
            }
            go.countDown();
            for (Future<Integer> request : requests) {
                statuses.add(request.get());
            }
        }

        assertThat(statuses).containsOnly(201, 409).containsOnlyOnce(201);
        assertThat(reversalCount(funding.id())).isOne();
        assertThat(balance(holder)).isZero();
    }

    private JournalEntry transfer(Account from, Account to, long amount) {
        return posting.postTransfer(key(), new TransferRequest(from.id(), to.id(), amount, "CAD", null, null)).entry();
    }

    private String reverse(UUID entryId, String key, int expectedStatus) {
        return client.post().uri("/entries/{id}/reverse", entryId)
                .header("Idempotency-Key", key)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private int reverseStatus(UUID entryId) {
        return client.post().uri("/entries/{id}/reverse", entryId)
                .header("Idempotency-Key", key())
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
    }

    private long balance(Account account) {
        return jdbc.sql("select balance from balances where account_id = :id")
                .param("id", account.id())
                .query(Long.class)
                .single();
    }

    private int lineCount(Account account) {
        return jdbc.sql("select count(*) from journal_lines where account_id = :id")
                .param("id", account.id())
                .query(Integer.class)
                .single();
    }

    private int lineCount(UUID entryId) {
        return jdbc.sql("select count(*) from journal_lines where entry_id = :id")
                .param("id", entryId)
                .query(Integer.class)
                .single();
    }

    private int reversalCount(UUID entryId) {
        return jdbc.sql("select count(*) from journal_entries where reverses_id = :id")
                .param("id", entryId)
                .query(Integer.class)
                .single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

}
