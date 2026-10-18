package dev.jenishsoni.tally.balance;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Locks the account's balance row until the surrounding transaction ends and returns the
     * balance. Only the posting transaction may call this, and it must lock accounts in
     * ascending id order so two postings can never wait on each other.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long lockBalance(UUID accountId) {
        return repository.findBalanceForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " has no balance row"));
    }

    /** Stores a balance computed from {@link #lockBalance}, so the caller must still hold that lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void storeBalance(UUID accountId, long balance) {
        repository.updateBalance(accountId, balance);
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
