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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.jenishsoni.tally.account.Account;
import dev.jenishsoni.tally.account.AccountService;
import dev.jenishsoni.tally.balance.BalanceService;
import dev.jenishsoni.tally.common.ConflictException;
import dev.jenishsoni.tally.common.Money;
import dev.jenishsoni.tally.common.NotFoundException;
import dev.jenishsoni.tally.common.UnprocessableException;
import dev.jenishsoni.tally.idempotency.Claim;
import dev.jenishsoni.tally.idempotency.IdempotencyStore;
import dev.jenishsoni.tally.idempotency.RequestHash;
import dev.jenishsoni.tally.outbox.OutboxWriter;
import tools.jackson.databind.json.JsonMapper;

@Service
class PostingServiceImpl implements PostingService {

    // Ascending account id as PostgreSQL orders uuid (unsigned bytes). UUID.compareTo is signed and would
    // differ, which would let a later `order by account_id for update` deadlock against a posting.
    private static final Comparator<UUID> LOCK_ORDER = Comparator.comparing(UUID::toString);

    // Only successful postings are saved; a refused one rolls back with its key, which can then be retried.
    private static final int CREATED = 201;

    // Every posting announces the same event type; the entry's kind is in the payload.
    private static final String ENTRY_POSTED = "entry.posted";

    private final AccountService accounts;
    private final BalanceService balances;
    private final JournalRepository journal;
    private final IdempotencyStore idempotency;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    PostingServiceImpl(AccountService accounts, BalanceService balances, JournalRepository journal,
            IdempotencyStore idempotency, OutboxWriter outbox, JsonMapper json) {
        this.accounts = accounts;
        this.balances = balances;
        this.journal = journal;
        this.idempotency = idempotency;
        this.outbox = outbox;
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
                () -> post("transfer", null, request.description(), request.effectiveAt(), lines));
    }

    @Override
    @Transactional
    public PostingResult postEntry(String idempotencyKey, EntryRequest request) {
        return postOnce(idempotencyKey, "entry", request,
                () -> post("entry", null, request.description(), request.effectiveAt(), request.lines()));
    }

    @Override
    @Transactional
    public PostingResult reverseEntry(String idempotencyKey, UUID entryId) {
        return postOnce(idempotencyKey, "reverse", entryId, () -> {
            JournalEntry original = journal.findEntry(entryId)
                    .orElseThrow(() -> new NotFoundException("Entry " + entryId + " not found"));
            return post("reversal", entryId, "Reversal of " + entryId, null, mirrorOf(original));
        });
    }

    // Step 2. A replay returns here without touching any balance, so a retry never moves money twice.
    private PostingResult postOnce(String idempotencyKey, String operation, Object request,
            Supplier<JournalEntry> posting) {
        String requestHash = RequestHash.of(operation, json.writeValueAsString(request));
        return switch (idempotency.claim(idempotencyKey, requestHash)) {
            case Claim.Claimed _ -> announceAndSave(idempotencyKey, posting.get());
            case Claim.Replay replay -> replayed(replay);
            case Claim.Conflict _ ->
                throw new ConflictException("Idempotency-Key was already used for a different request");
        };
    }

    // Steps 7 and 8 store one serialization, so Kafka, the caller and any retry all get the same entry.
    private PostingResult announceAndSave(String idempotencyKey, JournalEntry entry) {
        String body = json.writeValueAsString(entry);
        outbox.write(entry.id(), ENTRY_POSTED, body);
        idempotency.saveResponse(idempotencyKey, CREATED, body);
        return new PostingResult(CREATED, entry, false);
    }

    private PostingResult replayed(Claim.Replay replay) {
        JournalEntry entry = json.readValue(replay.body(), JournalEntry.class);
        return new PostingResult(replay.status(), entry, true);
    }

    private List<LineRequest> mirrorOf(JournalEntry original) {
        return original.lines().stream()
                .map(line -> new LineRequest(line.accountId(), negate(line.amount(), line.currency()), line.currency()))
                .toList();
    }

    private JournalEntry post(String kind, UUID reversesId, String description, Instant effectiveAt,
            List<LineRequest> lines) {
        requireBalanced(lines);
        Map<UUID, Account> touched = loadAccounts(lines);
        requireAccountCurrencies(lines, touched);
        Map<UUID, Money> changes = netChangePerAccount(lines);
        Map<UUID, Long> current = lockBalances(changes);
        requireNotReversedYet(reversesId);
        Map<UUID, Long> updated = requireNoOverdraft(touched, changes, current);
        JournalEntry entry = insertEntry(kind, reversesId, description, effectiveAt, lines);
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

    // A reversal locks the same accounts as the entry it undoes, so once those locks are held a competing
    // reversal has committed and is visible here. Checked before the overdraft test so a second reversal
    // is always a conflict, whatever the balances now are. The unique index on reverses_id is the backstop.
    private void requireNotReversedYet(UUID reversesId) {
        if (reversesId != null && journal.existsReversalOf(reversesId)) {
            throw new ConflictException("Entry " + reversesId + " was already reversed");
        }
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
    private JournalEntry insertEntry(String kind, UUID reversesId, String description, Instant effectiveAt,
            List<LineRequest> lines) {
        UUID id = UUID.randomUUID();
        JournalRepository.StoredEntry stored = journal.insertEntry(id, kind, reversesId, description, effectiveAt);
        List<JournalLine> saved = new ArrayList<>();
        for (LineRequest line : lines) {
            long lineId = journal.insertLine(id, line);
            saved.add(new JournalLine(lineId, line.accountId(), line.amount(), line.currency()));
        }
        return new JournalEntry(id, kind, reversesId, description, stored.effectiveAt(), stored.createdAt(), saved);
    }

    // Overflow must become a refusal, not a wrapped number or a 500.
    private static Money plus(Money a, Money b) {
        try {
            return a.plus(b);
        } catch (ArithmeticException e) {
            throw new UnprocessableException("Amounts are too large to add exactly");
        }
    }

    private static long negate(long amount, String currency) {
        try {
            return new Money(amount, currency).negate().minor();
        } catch (ArithmeticException e) {
            throw new UnprocessableException("Amount is too large to reverse exactly");
        }
    }

}
