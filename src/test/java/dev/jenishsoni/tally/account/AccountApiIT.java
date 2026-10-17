package dev.jenishsoni.tally.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import dev.jenishsoni.tally.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class AccountApiIT {

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void createsAnAccountWithAZeroBalanceAndFetchesIt() {
        String code = uniqueCode();

        String location = client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "currency", "CAD", "allowNegative", true))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .returnResult()
                .getResponseHeaders().getLocation().getPath();

        client.get().uri(location)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(code)
                .jsonPath("$.currency").isEqualTo("CAD")
                .jsonPath("$.allowNegative").isEqualTo(true)
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.createdAt").isNotEmpty();

        UUID id = UUID.fromString(location.substring("/accounts/".length()));
        long balance = jdbc.sql("select balance from balances where account_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
        assertThat(balance).isZero();
    }

    @Test
    void allowNegativeDefaultsToFalse() {
        client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", uniqueCode(), "currency", "USD"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.allowNegative").isEqualTo(false);
    }

    @Test
    void duplicateCodeIsA409ProblemAndLeavesNoExtraRows() {
        String code = uniqueCode();
        createAccount(code, "CAD");

        client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "currency", "CAD"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.title").isEqualTo("Conflict");

        long accounts = jdbc.sql("select count(*) from accounts where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
        assertThat(accounts).isEqualTo(1);
    }

    @Test
    void badCurrencyIsA400Problem() {
        client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", uniqueCode(), "currency", "cad"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.errors[0].field").isEqualTo("currency");
    }

    @Test
    void missingCodeIsA400Problem() {
        client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currency", "CAD"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("code");
    }

    @Test
    void malformedJsonIsA400Problem() {
        client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void unknownIdIsA404Problem() {
        client.get().uri("/accounts/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404);
    }

    @Test
    void idThatIsNotAUuidIsA400Problem() {
        client.get().uri("/accounts/not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private void createAccount(String code, String currency) {
        client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "currency", currency))
                .exchange()
                .expectStatus().isCreated();
    }

    // Tests share one database, so each uses its own code instead of cleaning up.
    private static String uniqueCode() {
        return "acct-" + UUID.randomUUID();
    }

}
