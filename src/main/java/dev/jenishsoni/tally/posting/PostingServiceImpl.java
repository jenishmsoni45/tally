package dev.jenishsoni.tally.posting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.balance.BalanceService;
import dev.jenishsoni.tally.common.Money;
import dev.jenishsoni.tally.common.UnprocessableException;

@Service
class PostingServiceImpl implements PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingServiceImpl.class);

    private final AccountService accounts;
    private final BalanceService balances;
    private final JournalRepository journal;

    PostingServiceImpl(AccountService accounts, BalanceService balances, JournalRepository journal) {
        this.accounts = accounts;
        this.balances = balances;
        this.journal = journal;
    }

    @Override
    @Transactional
    public JournalEntry postTransfer(TransferRequest request) {
        List<LineRequest> lines = List.of(
                new LineRequest(request.fromAccountId(), Math.negateExact(request.amount()), request.currency()),
                new LineRequest(request.toAccountId(), request.amount(), request.currency()));
        return post("transfer", request.description(), request.effectiveAt(), lines);
    }

    @Override
    @Transactional
    public JournalEntry postEntry(EntryRequest request) {
        return post("entry", request.description(), request.effectiveAt(), request.lines());
    }

    private JournalEntry post(String kind, String description, Instant effectiveAt, List<LineRequest> lines) {
        requireBalanced(lines);
        Map<UUID, Account> touched = loadAccounts(lines);
        requireAccountCurrencies(lines, touched);
        Map<UUID, Money> changes = netChangePerAccount(lines);
        Map<UUID, Long> current = lockBalances(changes);
        Map<UUID, Long> updated = requireNoOverdraft(touched, changes, current);
        JournalEntry entry = insertEntry(kind, description, effectiveAt, lines);
        updated.forEach(balances::storeBalance);
        log.info("Posted entry {} kind={} lines={}", entry.id(), kind, lines.size());
        return entry;
    }

    // Step 1: the lines must sum to zero in every currency (the database checks again at commit).
    private void requireBalanced(List<LineRequest> lines) {
        Map<String, Money> totals = new TreeMap<>();
        for (LineRequest line : lines) {
            totals.merge(line.currency(), new Money(line.amount(), line.currency()), PostingServiceImpl::plus);
        }
        totals.values().stream()
                .filter(total -> !total.isZero())
                .findFirst()
                .ifPresent(total -> {
                    throw new UnbalancedEntryException(
                            "Entry does not balance in " + total.currency() + ": lines sum to " + total.minor());
                });
    }

    // An unknown account is a 404 here, before any lock is taken.
    private Map<UUID, Account> loadAccounts(List<LineRequest> lines) {
        Map<UUID, Account> touched = new TreeMap<>();
        for (LineRequest line : lines) {
            touched.computeIfAbsent(line.accountId(), accounts::get);
        }
        return touched;
    }

    private void requireAccountCurrencies(List<LineRequest> lines, Map<UUID, Account> touched) {
        for (LineRequest line : lines) {
            String accountCurrency = touched.get(line.accountId()).currency();
            if (!accountCurrency.equals(line.currency())) {
                throw new UnprocessableException("Account " + line.accountId() + " holds " + accountCurrency
                        + ", not " + line.currency());
            }
        }
    }

    // The map is sorted by account id, which is the order step 3 locks in.
    private Map<UUID, Money> netChangePerAccount(List<LineRequest> lines) {
        Map<UUID, Money> changes = new TreeMap<>();
        for (LineRequest line : lines) {
            changes.merge(line.accountId(), new Money(line.amount(), line.currency()), PostingServiceImpl::plus);
        }
        return changes;
    }

    // Step 3: ascending account id, the same order in every posting, so two postings never wait on each other.
    private Map<UUID, Long> lockBalances(Map<UUID, Money> changes) {
        Map<UUID, Long> current = new TreeMap<>();
        for (UUID accountId : changes.keySet()) {
            current.put(accountId, balances.lockBalance(accountId));
        }
        return current;
    }

    // Step 4: the new balances, refusing any that would take an account that disallows it below zero.
    private Map<UUID, Long> requireNoOverdraft(Map<UUID, Account> touched, Map<UUID, Money> changes,
            Map<UUID, Long> current) {
        Map<UUID, Long> updated = new TreeMap<>();
        changes.forEach((accountId, change) -> {
            Money next = plus(new Money(current.get(accountId), change.currency()), change);
            if (next.minor() < 0 && !touched.get(accountId).allowNegative()) {
                throw new InsufficientFundsException("Account " + accountId + " has "
                        + current.get(accountId) + " but the entry needs " + -change.minor());
            }
            updated.put(accountId, next.minor());
        });
        return updated;
    }

    // Step 5. Lines go in only after the balance locks are held, so line ids on one account follow commit order.
    private JournalEntry insertEntry(String kind, String description, Instant effectiveAt, List<LineRequest> lines) {
        UUID id = UUID.randomUUID();
        JournalRepository.StoredEntry stored = journal.insertEntry(id, kind, description, effectiveAt);
        List<JournalLine> saved = new ArrayList<>();
        for (LineRequest line : lines) {
            long lineId = journal.insertLine(id, line);
            saved.add(new JournalLine(lineId, line.accountId(), line.amount(), line.currency()));
        }
        return new JournalEntry(id, kind, null, description, stored.effectiveAt(), stored.createdAt(), saved);
    }

    // Overflow must become a refusal, not a wrapped number or a 500.
    private static Money plus(Money a, Money b) {
        try {
            return a.plus(b);
        } catch (ArithmeticException e) {
            throw new UnprocessableException("Amounts are too large to add exactly");
        }
    }

}
