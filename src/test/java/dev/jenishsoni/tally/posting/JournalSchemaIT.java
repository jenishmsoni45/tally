package dev.jenishsoni.tally.posting;

import static dev.jenishsoni.tally.LedgerFixtures.account;
import static dev.jenishsoni.tally.LedgerFixtures.insertEntry;
import static dev.jenishsoni.tally.LedgerFixtures.insertLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.jenishsoni.tally.TestcontainersConfiguration;
import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;

/** The journal rules PostgreSQL enforces on its own, exercised with raw SQL as the application's role. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JournalSchemaIT {

    private static final String CHECK_VIOLATION = "23514";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccountService accounts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void theApplicationConnectsAsTallyApp() {
        String user = jdbc.sql("select current_user").query(String.class).single();

        assertThat(user).isEqualTo("tally_app");
    }

    @Test
    void aBalancedEntryCommits() {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);
        UUID entryId = UUID.randomUUID();

        inTransaction(() -> {
            insertEntry(jdbc, entryId);
            insertLine(jdbc, entryId, a.id(), 500, "CAD");
            insertLine(jdbc, entryId, b.id(), -500, "CAD");
        });

        assertThat(lineCount(entryId)).isEqualTo(2);
    }

    @Test
    void anUnbalancedEntryFailsAtCommitNotAtInsert() {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);
        UUID entryId = UUID.randomUUID();
        AtomicBoolean allInsertsAccepted = new AtomicBoolean();

        assertThatThrownBy(() -> inTransaction(() -> {
            insertEntry(jdbc, entryId);
            insertLine(jdbc, entryId, a.id(), 100, "CAD");
            insertLine(jdbc, entryId, b.id(), -90, "CAD");
            allInsertsAccepted.set(true);
        }))
                .hasMessageContaining("does not balance")
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION));

        assertThat(allInsertsAccepted).isTrue();
        assertThat(lineCount(entryId)).isZero();
    }

    @Test
    void anEntryMustBalanceInEachCurrencySeparately() {
        Account cad = account(accounts, "CAD", true);
        Account usd = account(accounts, "USD", true);
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> {
            insertEntry(jdbc, entryId);
            insertLine(jdbc, entryId, cad.id(), 100, "CAD");
            insertLine(jdbc, entryId, usd.id(), -100, "USD");
        })).hasMessageContaining("does not balance");

        assertThat(lineCount(entryId)).isZero();
    }

    @Test
    void aLineWithAmountZeroIsRejected() {
        Account a = account(accounts, "CAD", true);
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> {
            insertEntry(jdbc, entryId);
            insertLine(jdbc, entryId, a.id(), 0, "CAD");
        })).rootCause().isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update journal_lines set amount = 1",
            "delete from journal_lines",
            "update journal_entries set description = 'changed'",
            "delete from journal_entries" })
    void theApplicationRoleCannotChangeOrDeleteJournalRows(String statement) {
        Account a = account(accounts, "CAD", true);
        Account b = account(accounts, "CAD", true);
        UUID entryId = UUID.randomUUID();
        inTransaction(() -> {
            insertEntry(jdbc, entryId);
            insertLine(jdbc, entryId, a.id(), 7, "CAD");
            insertLine(jdbc, entryId, b.id(), -7, "CAD");
        });

        assertThatThrownBy(() -> jdbc.sql(statement).update())
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));

        assertThat(lineCount(entryId)).isEqualTo(2);
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private int lineCount(UUID entryId) {
        return jdbc.sql("select count(*) from journal_lines where entry_id = :id")
                .param("id", entryId)
                .query(Integer.class)
                .single();
    }

}
