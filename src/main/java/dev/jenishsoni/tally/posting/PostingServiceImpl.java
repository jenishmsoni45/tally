package dev.jenishsoni.tally.posting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.balance.BalanceService;
import dev.jenishsoni.tally.common.ConflictException;
import dev.jenishsoni.tally.common.Money;
import dev.jenishsoni.tally.common.UnprocessableException;
import dev.jenishsoni.tally.idempotency.Claim;
import dev.jenishsoni.tally.idempotency.IdempotencyStore;
import dev.jenishsoni.tally.idempotency.RequestHash;
import tools.jackson.databind.json.JsonMapper;

@Service
class PostingServiceImpl implements PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingServiceImpl.class);

    // Ascending account id as PostgreSQL orders uuid (unsigned bytes). UUID.compareTo is signed and would
    // differ, which would let a later `order by account_id for update` deadlock against a posting.
    private static final Comparator<UUID> LOCK_ORDER = Comparator.comparing(UUID::toString);

    // Only successful postings are saved; a refused one rolls back with its key, which can then be retried.
    private static final int CREATED = 201;

    private final AccountService accounts;
    private final BalanceService balances;
    private final JournalRepository journal;
    private final IdempotencyStore idempotency;
    private final JsonMapper json;

    PostingServiceImpl(AccountService accounts, BalanceService balances, JournalRepository journal,
            IdempotencyStore idempotency, JsonMapper json) {
        this.accounts = accounts;
        this.balances = balances;
        this.journal = journal;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    @Transactional
    public PostingResult postTransfer(String idempotencyKey, TransferRequest request) {
        requireValidTransfer(request);
        List<LineRequest> lines = List.of(
                new LineRequest(request.fromAccountId(), Math.negateExact(request.amount()), request.currency()),
                new LineRequest(request.toAccountId(), request.amount(), request.currency()));
        return postOnce(idempotencyKey, "transfer", request,
                () -> post("transfer", request.description(), request.effectiveAt(), lines));
    }

    @Override
    @Transactional
    public PostingResult postEntry(String idempotencyKey, EntryRequest request) {
        return postOnce(idempotencyKey, "entry", request,
                () -> post("entry", request.description(), request.effectiveAt(), request.lines()));
    }

    // Step 2. A replay returns here without touching any balance, so a retry never moves money twice.
    private PostingResult postOnce(String idempotencyKey, String operation, Object request,
            Supplier<JournalEntry> posting) {
        String requestHash = RequestHash.of(operation, json.writeValueAsString(request));
        return switch (idempotency.claim(idempotencyKey, requestHash)) {
            case Claim.Claimed claimed -> saveResponse(idempotencyKey, posting.get());
            case Claim.Replay replay -> replayed(idempotencyKey, replay);
            case Claim.Conflict conflict ->
                throw new ConflictException("Idempotency-Key was already used for a different request");
        };
    }

    // Step 8.
    private PostingResult saveResponse(String idempotencyKey, JournalEntry entry) {
        idempotency.saveResponse(idempotencyKey, CREATED, json.writeValueAsString(entry));
        log.info("Posted entry {} kind={} lines={} idempotencyKey={}",
                entry.id(), entry.kind(), entry.lines().size(), idempotencyKey);
        return new PostingResult(CREATED, entry);
    }

    private PostingResult replayed(String idempotencyKey, Claim.Replay replay) {
        JournalEntry entry = json.readValue(replay.body(), JournalEntry.class);
        log.info("Replayed entry {} idempotencyKey={}", entry.id(), idempotencyKey);
        return new PostingResult(replay.status(), entry);
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
        return entry;
    }

    // The controller validates these too; callers inside the service must not be able to flip a transfer's direction.
    private void requireValidTransfer(TransferRequest request) {
        if (request.amount() <= 0) {
            throw new IllegalArgumentException("A transfer amount must be positive");
        }
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new IllegalArgumentException("A transfer needs two different accounts");
        }
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
        Map<UUID, Account> touched = new HashMap<>();
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

    // The only ordered map: its iteration order is the order step 3 locks in.
    private Map<UUID, Money> netChangePerAccount(List<LineRequest> lines) {
        Map<UUID, Money> changes = new TreeMap<>(LOCK_ORDER);
        for (LineRequest line : lines) {
            changes.merge(line.accountId(), new Money(line.amount(), line.currency()), PostingServiceImpl::plus);
        }
        return changes;
    }

    // Step 3: ascending account id, the same order in every posting, so two postings never wait on each other.
    private Map<UUID, Long> lockBalances(Map<UUID, Money> changes) {
        Map<UUID, Long> current = new HashMap<>();
        for (UUID accountId : changes.keySet()) {
            current.put(accountId, balances.lockBalance(accountId));
        }
        return current;
    }

    // Step 4: the new balances, refusing any that would take an account that disallows it below zero.
    private Map<UUID, Long> requireNoOverdraft(Map<UUID, Account> touched, Map<UUID, Money> changes,
            Map<UUID, Long> current) {
        Map<UUID, Long> updated = new HashMap<>();
        changes.forEach((accountId, change) -> {
            Money next = plus(new Money(current.get(accountId), change.currency()), change);
            if (next.minor() < 0 && !touched.get(accountId).allowNegative()) {
                throw new InsufficientFundsException("Account " + accountId + " has " + current.get(accountId)
                        + " and the entry changes it by " + change.minor());
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
