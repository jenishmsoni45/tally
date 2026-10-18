package dev.jenishsoni.tally;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.account.CreateAccountRequest;

/** Test setup helpers. The raw SQL ones bypass the posting service on purpose, to test the schema itself. */
public final class LedgerFixtures {

    private LedgerFixtures() {
    }

    public static Account account(AccountService accounts, String currency, boolean allowNegative) {
        return accounts.create(new CreateAccountRequest("it-" + UUID.randomUUID(), currency, allowNegative));
    }

    public static void insertEntry(JdbcClient jdbc, UUID entryId) {
        jdbc.sql("insert into journal_entries (id, kind, effective_at) values (:id, 'entry', now())")
                .param("id", entryId)
                .update();
    }

    public static void insertLine(JdbcClient jdbc, UUID entryId, UUID accountId, long amount, String currency) {
        jdbc.sql("""
                insert into journal_lines (entry_id, account_id, amount, currency)
                values (:entryId, :accountId, :amount, :currency)
                """)
                .param("entryId", entryId)
                .param("accountId", accountId)
                .param("amount", amount)
                .param("currency", currency)
                .update();
    }

}
