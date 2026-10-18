package dev.jenishsoni.tally.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.jenishsoni.tally.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotencyStoreIT {

    private static final String BODY = "{\"id\":\"abc\"}";

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aFirstClaimWinsAndACommittedRetryReplaysTheSavedResponse() {
        String key = newKey();

        Claim first = inTransaction(() -> {
            Claim claim = store.claim(key, "hash-a");
            store.saveResponse(key, 201, BODY);
            return claim;
        });
        Claim retry = inTransaction(() -> store.claim(key, "hash-a"));

        assertThat(first).isInstanceOf(Claim.Claimed.class);
        // jsonb keeps the document but not its whitespace.
        assertThat(retry).isInstanceOfSatisfying(Claim.Replay.class, replay -> {
            assertThat(replay.status()).isEqualTo(201);
            assertThat(replay.body()).isEqualToIgnoringWhitespace(BODY);
        });
    }

    @Test
    void theSameKeyWithAnotherRequestHashIsAConflict() {
        String key = newKey();
        inTransaction(() -> {
            store.claim(key, "hash-a");
            store.saveResponse(key, 201, BODY);
            return null;
        });

        Claim claim = inTransaction(() -> store.claim(key, "hash-b"));

        assertThat(claim).isInstanceOf(Claim.Conflict.class);
    }

    @Test
    void aRolledBackClaimLeavesTheKeyFree() {
        String key = newKey();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            store.claim(key, "hash-a");
            status.setRollbackOnly();
        });

        Claim claim = inTransaction(() -> store.claim(key, "hash-a"));

        assertThat(claim).isInstanceOf(Claim.Claimed.class);
    }

    @Test
    void aKeyWithoutAResponseIsReportedNotReplayed() {
        String key = newKey();
        jdbc.sql("insert into idempotency_keys (key, request_hash) values (:key, 'hash-a')")
                .param("key", key)
                .update();

        assertThatThrownBy(() -> inTransaction(() -> store.claim(key, "hash-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key);
    }

    @Test
    void claimingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> store.claim(newKey(), "hash-a"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> store.saveResponse(newKey(), 201, BODY))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theSameRequestFingerprintsTheSameAndAnotherOperationDoesNot() {
        assertThat(RequestHash.of("transfer", "{\"a\":1}")).isEqualTo(RequestHash.of("transfer", "{\"a\":1}"));
        assertThat(RequestHash.of("transfer", "{\"a\":1}")).isNotEqualTo(RequestHash.of("entry", "{\"a\":1}"));
        assertThat(RequestHash.of("transfer", "{\"a\":1}")).hasSize(64);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

}
