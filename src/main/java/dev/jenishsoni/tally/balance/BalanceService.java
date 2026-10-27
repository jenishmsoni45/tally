package dev.jenishsoni.tally.balance;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.common.NotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class BalanceService {

    private final AccountService accounts;
    private final BalanceRepository repository;
    private final Timer lockWait;

    BalanceService(AccountService accounts, BalanceRepository repository, MeterRegistry registry) {
        this.accounts = accounts;
        this.repository = repository;
        this.lockWait = Timer.builder("ledger.lock.wait")
                .description("Time to lock a posting's balance rows")
                .register(registry);
    }

    public BalanceResponse balanceOf(UUID accountId) {
        Account account = accounts.get(accountId);
        BalanceRepository.StoredBalance stored = repository.findBalance(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " has no balance row"));
        return new BalanceResponse(accountId, stored.balance(), account.currency(), stored.updatedAt());
    }

    /**
     * The sum of the account's lines in entries created at or before asOf, which is what the balance was then.
     * An entry's created_at is its transaction's start time, so a read made while an earlier-started
     * posting is still committing can miss it, and the same asOf answers differently once it commits.
     */
    public AsOfBalanceResponse balanceAsOf(UUID accountId, Instant asOf) {
        Account account = accounts.get(accountId);
        return new AsOfBalanceResponse(accountId, repository.sumLinesCreatedAtOrBefore(accountId, asOf),
                account.currency(), asOf);
    }

    /**
     * Locks the accounts' balance rows until the surrounding transaction ends and returns the
     * balances. Only the posting transaction may call this. One statement locks all the rows in
     * ascending account id order, the same order in every posting, so two postings can never
     * wait on each other.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Long> lockBalances(Collection<UUID> accountIds) {
        // Times the SELECT ... FOR UPDATE alone: waiting for other postings' row locks plus one round trip.
        Map<UUID, Long> locked = lockWait.record(() -> repository.findBalancesForUpdate(accountIds));
        accountIds.stream()
                .filter(accountId -> !locked.containsKey(accountId))
                .findFirst()
                .ifPresent(accountId -> {
                    throw new NotFoundException("Account " + accountId + " has no balance row");
                });
        return locked;
    }

    /** Stores balances computed from {@link #lockBalances}, so the caller must still hold those locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void storeBalances(Map<UUID, Long> balances) {
        repository.updateBalances(balances);
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
