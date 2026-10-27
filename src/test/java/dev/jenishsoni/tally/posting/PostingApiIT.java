package dev.jenishsoni.tally.posting;

import static dev.jenishsoni.tally.LedgerFixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
class PostingApiIT {

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccountService accounts;

    @Autowired
    private PostingService posting;

    @Test
    void aTransferMovesMoneyAndIsVisibleInBalancesAndEntries() {
        Account source = account(accounts, "CAD", true);
        Account destination = account(accounts, "CAD", false);

        client.post().uri("/transfers")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fromAccountId", source.id(), "toAccountId", destination.id(),
                        "amount", 2500, "currency", "CAD", "description", "rent"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.kind").isEqualTo("transfer")
                .jsonPath("$.description").isEqualTo("rent")
                .jsonPath("$.lines.length()").isEqualTo(2);

        assertBalance(destination.id(), 2500);
        assertBalance(source.id(), -2500);
        client.get().uri("/accounts/{id}/entries", destination.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].amount").isEqualTo(2500);
    }

    @Test
    void anUnbalancedEntryIs422AndLeavesNoTrace() {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);

        client.post().uri("/entries")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of(line(a.id(), 100), line(b.id(), -90))))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        assertNoLines(a.id(), b.id());
        assertBalance(a.id(), 0);
        assertBalance(b.id(), 0);
    }

    @Test
    void anOverdraftIs422AndLeavesNoTrace() {
        Account poor = account(accounts, "CAD", false);
        Account other = account(accounts, "CAD", false);

        client.post().uri("/transfers")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fromAccountId", poor.id(), "toAccountId", other.id(),
                        "amount", 1, "currency", "CAD"))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        assertNoLines(poor.id(), other.id());
        assertBalance(poor.id(), 0);
    }

    @Test
    void aLineWithAmountZeroIs400() {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);

        client.post().uri("/entries")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of(line(a.id(), 0), line(b.id(), 5))))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("lines[0].amount");
    }

    @Test
    void anEntryWithoutLinesIs400() {
        client.post().uri("/entries")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of()))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void aNonPositiveTransferAmountIs400() {
        client.post().uri("/transfers")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fromAccountId", UUID.randomUUID(), "toAccountId", UUID.randomUUID(),
                        "amount", -5, "currency", "CAD"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void aFractionalAmountIs400NotTruncated() {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);

        client.post().uri("/transfers")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"fromAccountId\":\"" + a.id() + "\",\"toAccountId\":\"" + b.id()
                        + "\",\"amount\":100.75,\"currency\":\"CAD\"}")
                .exchange()
                .expectStatus().isBadRequest();
        client.post().uri("/entries")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"lines\":[{\"accountId\":\"" + a.id() + "\",\"amount\":100.75,\"currency\":\"CAD\"},"
                        + "{\"accountId\":\"" + b.id() + "\",\"amount\":-100.75,\"currency\":\"CAD\"}]}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void aTransferToTheSameAccountIs400() {
        Account a = account(accounts, "CAD", true);

        client.post().uri("/transfers")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fromAccountId", a.id(), "toAccountId", a.id(), "amount", 5, "currency", "CAD"))
                .exchange()
                .expectStatus().isBadRequest();

        assertNoLines(a.id());
    }

    @Test
    void anUnknownAccountIs404AndLeavesNoTrace() {
        Account a = account(accounts, "CAD", true);

        client.post().uri("/entries")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of(line(a.id(), 10), line(UUID.randomUUID(), -10))))
                .exchange()
                .expectStatus().isNotFound();

        assertNoLines(a.id());
    }

    @Test
    void aLineInTheWrongCurrencyIs422() {
        Account cad = account(accounts, "CAD", true);
        Account usd = account(accounts, "USD", true);

        client.post().uri("/entries")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of(line(cad.id(), 10), line(usd.id(), -10))))
                .exchange()
                .expectStatus().isEqualTo(422);

        assertNoLines(cad.id(), usd.id());
    }

    @Test
    void amountsThatOverflowAreRefusedNotWrapped() {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);
        Account c = account(accounts, "CAD", true);

        client.post().uri("/entries")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of(line(a.id(), Long.MAX_VALUE), line(b.id(), Long.MAX_VALUE),
                        line(c.id(), -2L))))
                .exchange()
                .expectStatus().isEqualTo(422);

        assertNoLines(a.id(), b.id(), c.id());
    }

    @Test
    void theOverdraftCheckUsesTheNetChangeOfAnAccountNotEachLine() {
        Account poor = account(accounts, "CAD", false);
        Account other = account(accounts, "CAD", true);

        client.post().uri("/entries")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of(line(poor.id(), -50), line(poor.id(), 50),
                        line(other.id(), 10), line(other.id(), -10))))
                .exchange()
                .expectStatus().isCreated();
        assertBalance(poor.id(), 0);

        client.post().uri("/entries")
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of(line(poor.id(), -50), line(poor.id(), 30), line(other.id(), 20))))
                .exchange()
                .expectStatus().isEqualTo(422);
        assertBalance(poor.id(), 0);
        assertBalance(other.id(), 0);
    }

    @Test
    void theServiceRefusesATransferThatSkippedTheControllerChecks() {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);

        assertThatThrownBy(() -> posting.postTransfer(key(), new TransferRequest(a.id(), a.id(), 5L, "CAD", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> posting.postTransfer(key(), new TransferRequest(a.id(), b.id(), -5L, "CAD", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertNoLines(a.id(), b.id());
    }

    @Test
    void theServiceRefusesAnEntryWithoutLinesThatSkippedTheControllerChecks() {
        assertThatThrownBy(() -> posting.postEntry(key(), new EntryRequest(null, null, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertBalance(UUID accountId, long expected) {
        client.get().uri("/accounts/{id}/balance", accountId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.balance").isEqualTo(expected);
    }

    private void assertNoLines(UUID... accountIds) {
        for (UUID accountId : accountIds) {
            int count = jdbc.sql("select count(*) from journal_lines where account_id = :id")
                    .param("id", accountId)
                    .query(Integer.class)
                    .single();
            assertThat(count).isZero();
        }
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static Map<String, Object> line(UUID accountId, long amount) {
        return Map.of("accountId", accountId, "amount", amount, "currency", "CAD");
    }

}
