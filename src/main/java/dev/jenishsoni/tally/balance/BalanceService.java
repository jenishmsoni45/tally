package dev.jenishsoni.tally.balance;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.common.NotFoundException;

@Service
public class BalanceService {

    private final AccountService accounts;
    private final BalanceRepository repository;

    BalanceService(AccountService accounts, BalanceRepository repository) {
        this.accounts = accounts;
        this.repository = repository;
    }

    public BalanceResponse balanceOf(UUID accountId) {
        Account account = accounts.get(accountId);
        BalanceRepository.StoredBalance stored = repository.findBalance(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " has no balance row"));
        return new BalanceResponse(accountId, stored.balance(), account.currency(), stored.updatedAt());
    }

    /** Keyset paging on line id: cursor 0 starts at the beginning, and one extra row tells whether more exist. */
    public AccountLinePage linesOf(UUID accountId, long cursor, int limit) {
        accounts.get(accountId);
        List<AccountLine> rows = repository.findLinesAfter(accountId, cursor, limit + 1);
        if (rows.size() <= limit) {
            return new AccountLinePage(rows, null);
        }
        List<AccountLine> items = rows.subList(0, limit);
        return new AccountLinePage(items, items.getLast().lineId());
    }

}
