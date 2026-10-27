package dev.jenishsoni.tally.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntSupplier;

/**
 * The ledger's rules in memory, so a simulation can say what the API must answer to each request. Accounts and
 * entries are kept by index in creation order, entries only once posted. When several refusals apply the API
 * answers with the first it finds, so a posting makes its checks in the posting service's order: the
 * idempotency key, for a reversal an unknown entry, an unbalanced entry, an unknown account, a currency the
 * account does not hold, a second reversal and last an overdraft. An amount too large to add or negate exactly
 * is a 422 at the same steps. A refused posting changes nothing and saves no key, as the API's rollback does.
 */
final class LedgerModel {

    private static final int CREATED = 201;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int UNPROCESSABLE = 422;

    private final List<ModelAccount> accounts = new ArrayList<>();
    private final List<ModelEntry> entries = new ArrayList<>();
    private final Map<String, SavedPosting> keys = new HashMap<>();

    /**
     * What the API must answer: the HTTP status, and the index of the account or entry the request created or,
     * for a replay, the entry it created the first time; {@link #NONE} when it was refused.
     */
    record Outcome(int status, int index, boolean replayed) {

        static final int NONE = -1;

    }

    record ModelAccount(String code, String currency, boolean allowNegative, long balance) {
    }

    /** kind is transfer, entry or reversal; reverses is the reversed entry's index, or Outcome.NONE. */
    record ModelEntry(String kind, int reverses, List<Operation.Line> lines, boolean reversed) {
    }

    Outcome openAccount(String code, String currency, boolean allowNegative) {
        if (accounts.stream().anyMatch(account -> account.code().equals(code))) {
            return refused(CONFLICT);
        }
        accounts.add(new ModelAccount(code, currency, allowNegative, 0));
        return new Outcome(CREATED, accounts.size() - 1, false);
    }

    Outcome transfer(String key, Operation.Transfer transfer) {
        List<Operation.Line> lines = List.of(
                new Operation.Line(transfer.from(), Math.negateExact(transfer.amount()), transfer.currency()),
                new Operation.Line(transfer.to(), transfer.amount(), transfer.currency()));
        return postOnce(key, transfer, () -> post("transfer", Outcome.NONE, lines));
    }

    Outcome entry(String key, Operation.Entry entry) {
        return postOnce(key, entry, () -> post("entry", Outcome.NONE, entry.lines()));
    }

    // The entry is looked up inside postOnce, as in the API, so a key conflict wins over an unknown entry.
    Outcome reverse(String key, Operation.Reverse reverse) {
        return postOnce(key, reverse,
                () -> post("reversal", reverse.entry(), mirrorOf(postedEntry(reverse.entry()))));
    }

    List<ModelAccount> accounts() {
        return List.copyOf(accounts);
    }

    List<ModelEntry> entries() {
        return List.copyOf(entries);
    }

    Map<String, Long> totalsPerCurrency() {
        Map<String, Long> totals = new TreeMap<>();
        accounts.forEach(account -> totals.merge(account.currency(), account.balance(), Math::addExact));
        return totals;
    }

    // Records of different types are never equal, so a key saved by one operation conflicts with every other,
    // as the operation name in the API's request hash makes it.
    private Outcome postOnce(String key, Operation request, IntSupplier posting) {
        SavedPosting saved = keys.get(key);
        if (saved == null) {
            return postAndSave(key, request, posting);
        }
        if (!saved.request().equals(request)) {
            return refused(CONFLICT);
        }
        return new Outcome(CREATED, saved.entry(), true);
    }

    private Outcome postAndSave(String key, Operation request, IntSupplier posting) {
        try {
            int entry = posting.getAsInt();
            keys.put(key, new SavedPosting(request, entry));
            return new Outcome(CREATED, entry, false);
        } catch (Refused refusal) {
            return refused(refusal.status);
        }
    }

    private int post(String kind, int reverses, List<Operation.Line> lines) {
        requireBalanced(lines);
        requireKnownAccounts(lines);
        requireAccountCurrencies(lines);
        Map<Integer, Long> changes = netChangePerAccount(lines);
        requireNotReversedYet(reverses);
        Map<Integer, Long> updated = requireNoOverdraft(changes);
        updated.forEach(this::storeBalance);
        return append(kind, reverses, lines);
    }

    private ModelEntry postedEntry(int index) {
        if (index < 0 || index >= entries.size()) {
            throw new Refused(NOT_FOUND);
        }
        return entries.get(index);
    }

    private static List<Operation.Line> mirrorOf(ModelEntry original) {
        return original.lines().stream()
                .map(line -> new Operation.Line(line.account(), negate(line.amount()), line.currency()))
                .toList();
    }

    private static void requireBalanced(List<Operation.Line> lines) {
        Map<String, Long> totals = new HashMap<>();
        for (Operation.Line line : lines) {
            totals.merge(line.currency(), line.amount(), LedgerModel::plus);
        }
        if (totals.values().stream().anyMatch(total -> total != 0)) {
            throw new Refused(UNPROCESSABLE);
        }
    }

    private void requireKnownAccounts(List<Operation.Line> lines) {
        if (lines.stream().anyMatch(line -> line.account() < 0 || line.account() >= accounts.size())) {
            throw new Refused(NOT_FOUND);
        }
    }

    private void requireAccountCurrencies(List<Operation.Line> lines) {
        if (lines.stream().anyMatch(line -> !accounts.get(line.account()).currency().equals(line.currency()))) {
            throw new Refused(UNPROCESSABLE);
        }
    }

    // Summed in line order, as the API sums them, so an overflow is refused for the same entries.
    private static Map<Integer, Long> netChangePerAccount(List<Operation.Line> lines) {
        Map<Integer, Long> changes = new TreeMap<>();
        for (Operation.Line line : lines) {
            changes.merge(line.account(), line.amount(), LedgerModel::plus);
        }
        return changes;
    }

    // Before the overdraft check, as in the API, so a second reversal is a 409 whatever the balances are now.
    private void requireNotReversedYet(int reverses) {
        if (reverses != Outcome.NONE && entries.get(reverses).reversed()) {
            throw new Refused(CONFLICT);
        }
    }

    private Map<Integer, Long> requireNoOverdraft(Map<Integer, Long> changes) {
        Map<Integer, Long> updated = new TreeMap<>();
        changes.forEach((index, change) -> {
            ModelAccount account = accounts.get(index);
            long next = plus(account.balance(), change);
            if (next < 0 && !account.allowNegative()) {
                throw new Refused(UNPROCESSABLE);
            }
            updated.put(index, next);
        });
        return updated;
    }

    private void storeBalance(int index, long balance) {
        ModelAccount account = accounts.get(index);
        accounts.set(index, new ModelAccount(account.code(), account.currency(), account.allowNegative(), balance));
    }

    private int append(String kind, int reverses, List<Operation.Line> lines) {
        entries.add(new ModelEntry(kind, reverses, lines, false));
        if (reverses != Outcome.NONE) {
            ModelEntry original = entries.get(reverses);
            entries.set(reverses, new ModelEntry(original.kind(), original.reverses(), original.lines(), true));
        }
        return entries.size() - 1;
    }

    // Overflow is a 422 in the API, not a wrapped number or a 500.
    private static long plus(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new Refused(UNPROCESSABLE);
        }
    }

    private static long negate(long amount) {
        try {
            return Math.negateExact(amount);
        } catch (ArithmeticException e) {
            throw new Refused(UNPROCESSABLE);
        }
    }

    private static Outcome refused(int status) {
        return new Outcome(status, Outcome.NONE, false);
    }

    private record SavedPosting(Operation request, int entry) {
    }

    /** Thrown before anything has changed, so unwinding to postAndSave leaves the model as it was. */
    private static final class Refused extends RuntimeException {

        private final int status;

        Refused(int status) {
            super("Refused with status " + status);
            this.status = status;
        }

    }

}
