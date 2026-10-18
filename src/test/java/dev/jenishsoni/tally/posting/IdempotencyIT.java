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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** A retried request, or many at once, moves money exactly once and always gets the first response back. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class IdempotencyIT {

    private static final int CONCURRENT_REQUESTS = 20;

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccountService accounts;

    @Test
    void theSameKeyTwiceCreatesOneEntryAndReturnsTheSameResponse() {
        Account source = account(accounts, "CAD", true);
        Account destination = account(accounts, "CAD", true);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = transfer(source, destination, 700);

        String first = postTransfer(key, body, 201);
        String second = postTransfer(key, body, 201);

        assertThat(second).isEqualTo(first);
        assertThat(lineCount(source)).isOne();
        assertThat(lineCount(destination)).isOne();
        assertThat(balance(destination)).isEqualTo(700);
    }

    @Test
    void theSameRequestWithTheFieldsInAnotherOrderIsStillTheSameRequest() {
        Account source = account(accounts, "CAD", true);
        Account destination = account(accounts, "CAD", true);
        String key = UUID.randomUUID().toString();
        String inOneOrder = "{\"fromAccountId\":\"" + source.id() + "\",\"toAccountId\":\"" + destination.id()
                + "\",\"amount\":700,\"currency\":\"CAD\"}";
        String inAnotherOrder = "{\"currency\":\"CAD\",\"amount\":700,\"toAccountId\":\"" + destination.id()
                + "\",\"fromAccountId\":\"" + source.id() + "\"}";

        String first = postTransfer(key, inOneOrder, 201);
        String second = postTransfer(key, inAnotherOrder, 201);

        assertThat(second).isEqualTo(first);
        assertThat(balance(destination)).isEqualTo(700);
    }

    @Test
    void theSameKeyWithADifferentBodyIs409AndMovesNothing() {
        Account source = account(accounts, "CAD", true);
        Account destination = account(accounts, "CAD", true);
        String key = UUID.randomUUID().toString();
        postTransfer(key, transfer(source, destination, 700), 201);

        client.post().uri("/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transfer(source, destination, 701))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        assertThat(balance(destination)).isEqualTo(700);
        assertThat(lineCount(destination)).isOne();
    }

    @Test
    void theSameKeyOnAnotherEndpointIs409() {
        Account source = account(accounts, "CAD", true);
        Account destination = account(accounts, "CAD", true);
        String key = UUID.randomUUID().toString();
        postTransfer(key, transfer(source, destination, 700), 201);

        client.post().uri("/entries")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of(
                        Map.of("accountId", source.id(), "amount", -700, "currency", "CAD"),
                        Map.of("accountId", destination.id(), "amount", 700, "currency", "CAD"))))
                .exchange()
                .expectStatus().isEqualTo(409);

        assertThat(balance(destination)).isEqualTo(700);
    }

    @Test
    void aRefusedRequestDoesNotUseUpItsKey() {
        Account poor = account(accounts, "CAD", false);
        Account other = account(accounts, "CAD", false);
        Account treasury = account(accounts, "CAD", true);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = transfer(poor, other, 300);
        postTransfer(key, body, 422);
        postTransfer(UUID.randomUUID().toString(), transfer(treasury, poor, 300), 201);

        postTransfer(key, body, 201);

        assertThat(balance(other)).isEqualTo(300);
        assertThat(balance(poor)).isZero();
    }

    @Test
    void aMissingIdempotencyKeyIs400() {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);

        client.post().uri("/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(transfer(a, b, 5))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        client.post().uri("/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("lines", List.of(
                        Map.of("accountId", a.id(), "amount", -5, "currency", "CAD"),
                        Map.of("accountId", b.id(), "amount", 5, "currency", "CAD"))))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(lineCount(a)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "two words", "tab\there" })
    void aMalformedIdempotencyKeyIs400(String key) {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);

        client.post().uri("/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transfer(a, b, 5))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("Idempotency-Key");

        assertThat(lineCount(a)).isZero();
    }

    @Test
    void aKeyOfExactlyTheMaximumLengthIsAcceptedAndOneLongerIsNot() {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);

        postTransfer("k".repeat(255), transfer(a, b, 5), 201);
        postTransfer("k".repeat(256), transfer(a, b, 5), 400);

        assertThat(lineCount(a)).isOne();
    }

    @Test
    void twentyThreadsSendingTheSameKeyCreateExactlyOneEntry() throws Exception {
        Account source = account(accounts, "CAD", true);
        Account destination = account(accounts, "CAD", true);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = transfer(source, destination, 700);
        CountDownLatch go = new CountDownLatch(1);

        List<String> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
            List<Future<String>> requests = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                requests.add(pool.submit(() -> {
                    go.await();
                    return postTransfer(key, body, 201);
                }));
            }
            go.countDown();
            for (Future<String> request : requests) {
                responses.add(request.get());
            }
        }

        assertThat(responses).hasSize(CONCURRENT_REQUESTS).containsOnly(responses.getFirst());
        assertThat(lineCount(source)).isOne();
        assertThat(lineCount(destination)).isOne();
        assertThat(balance(destination)).isEqualTo(700);
        assertThat(balance(source)).isEqualTo(-700);
    }

    private String postTransfer(String key, Object body, int expectedStatus) {
        return client.post().uri("/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private static Map<String, Object> transfer(Account from, Account to, long amount) {
        return Map.of("fromAccountId", from.id(), "toAccountId", to.id(), "amount", amount, "currency", "CAD");
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

}
