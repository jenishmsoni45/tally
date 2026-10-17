package dev.jenishsoni.tally.balance;

import static dev.jenishsoni.tally.LedgerFixtures.account;
import static dev.jenishsoni.tally.LedgerFixtures.insertEntry;
import static dev.jenishsoni.tally.LedgerFixtures.insertLine;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.jenishsoni.tally.TestcontainersConfiguration;
import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;

/** The read endpoints, over rows seeded with raw SQL because the posting service is not built yet. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class BalanceApiIT {

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccountService accounts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void returnsTheStoredBalanceAndCurrency() {
        Account account = account(accounts, "USD", false);
        jdbc.sql("update balances set balance = 1234 where account_id = :id")
                .param("id", account.id())
                .update();

        client.get().uri("/accounts/{id}/balance", account.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accountId").isEqualTo(account.id().toString())
                .jsonPath("$.balance").isEqualTo(1234)
                .jsonPath("$.currency").isEqualTo("USD")
                .jsonPath("$.updatedAt").isNotEmpty();
    }

    @Test
    void anUnknownAccountIs404ForBalanceAndEntries() {
        UUID unknown = UUID.randomUUID();

        client.get().uri("/accounts/{id}/balance", unknown)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        client.get().uri("/accounts/{id}/entries", unknown)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void pagesThroughEntriesOldestFirstWithAKeysetCursor() {
        Account account = account(accounts, "CAD", true);
        Account counterparty = account(accounts, "CAD", true);
        for (int i = 1; i <= 5; i++) {
            seedEntry(account, counterparty, i * 100L);
        }

        AccountLinePage first = page(account, 0, 2);
        AccountLinePage second = page(account, first.nextCursor(), 2);
        AccountLinePage third = page(account, second.nextCursor(), 2);

        assertThat(first.items()).extracting(AccountLine::amount).containsExactly(100L, 200L);
        assertThat(second.items()).extracting(AccountLine::amount).containsExactly(300L, 400L);
        assertThat(third.items()).extracting(AccountLine::amount).containsExactly(500L);
        assertThat(first.items().getFirst().kind()).isEqualTo("entry");
        assertThat(first.nextCursor()).isEqualTo(first.items().getLast().lineId());
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void anExactlyFullLastPageHasNoNextCursor() {
        Account account = account(accounts, "CAD", true);
        Account counterparty = account(accounts, "CAD", true);
        seedEntry(account, counterparty, 100);
        seedEntry(account, counterparty, 200);

        assertThat(page(account, 0, 2).nextCursor()).isNull();
    }

    @Test
    void aLimitOutsideTheAllowedRangeIs400() {
        Account account = account(accounts, "CAD", false);

        for (int limit : new int[] { 0, 201 }) {
            client.get().uri("/accounts/{id}/entries?limit={limit}", account.id(), limit)
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    private AccountLinePage page(Account account, long cursor, int limit) {
        return client.get().uri("/accounts/{id}/entries?cursor={cursor}&limit={limit}", account.id(), cursor, limit)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AccountLinePage.class)
                .returnResult()
                .getResponseBody();
    }

    private void seedEntry(Account to, Account from, long amount) {
        UUID entryId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            insertEntry(jdbc, entryId);
            insertLine(jdbc, entryId, to.id(), amount, "CAD");
            insertLine(jdbc, entryId, from.id(), -amount, "CAD");
        });
    }

}
