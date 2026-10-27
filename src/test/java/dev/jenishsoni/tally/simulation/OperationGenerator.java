package dev.jenishsoni.tally.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import dev.jenishsoni.tally.simulation.LedgerModel.ModelAccount;
import dev.jenishsoni.tally.simulation.LedgerModel.ModelEntry;

/**
 * Chooses each operation of a simulation run in the plan's mix: open account 5%, transfer 60%, multi-line entry
 * 15%, reverse 10% and retry an earlier request 10%, a fifth of those retries reusing an earlier posting's key for a
 * different request. Most operations are ones the ledger accepts, so balances keep
 * moving; a few percent of each kind are built to be refused (an unknown account or entry, a wrong currency,
 * missing funds, lines that do not balance, a second reversal), so every rule the model copies is exercised. Every
 * choice comes from one {@link Random} and the model's state, so the same seed against the same model gives the
 * same operations, and a failing run is replayed from its seed alone.
 */
final class OperationGenerator {

    private static final List<String> CURRENCIES = List.of("CAD", "USD");

    // The plan's mix as cumulative percentages: open account 5%, transfer 60%, entry 15%, reverse 10%, and the
    // last 10% retry an earlier request.
    private static final int OPEN_ACCOUNT_BELOW = 5;
    private static final int TRANSFER_BELOW = 65;
    private static final int ENTRY_BELOW = 80;
    private static final int REVERSE_BELOW = 90;

    // At most 20.00 a payment, so a whole run's sums stay far from overflow. Only overdrafts and payments of a
    // whole balance follow a balance instead, and a balance is itself a sum of such payments.
    private static final long MAX_AMOUNT = 2_000;

    // About a third of the accounts after each currency's first may go negative.
    private static final int ALLOW_NEGATIVE_ONE_IN = 3;

    // The most lines the API accepts in one entry.
    private static final int MAX_LINES = 100;

    // An ordinary entry has up to 4 legs, 8 lines: enough to repeat accounts, short enough to read in a failure.
    private static final int MAX_LEGS = 4;

    // Refusals, in percent of the operations of their kind. Each is small, so most operations are accepted and
    // balances keep moving, yet a 500-step run has about 300 transfers, 75 entries and 50 reversals, so every rule
    // is hit in most runs and many times over 20 seeds.
    private static final int UNKNOWN_ACCOUNT_PERCENT = 3;
    private static final int WRONG_CURRENCY_PERCENT = 3;
    private static final int OVERDRAFT_PERCENT = 5;
    private static final int UNKNOWN_LINE_PERCENT = 5;
    private static final int UNBALANCED_PERCENT = 8;
    private static final int WRONG_CURRENCY_LEG_PERCENT = 5;
    private static final int UNKNOWN_ENTRY_PERCENT = 5;
    private static final int SECOND_REVERSAL_PERCENT = 15;

    // Of the retries: the client that reuses a key for a different request, the one way to provoke the 409 that
    // keeps a key from moving money for two requests.
    private static final int REUSED_KEY_PERCENT = 20;

    // Shapes of entries the ledger accepts, in percent of entries: passing money on through an account, both
    // currencies in one entry, and the most lines the API accepts. A pass-through can also be refused, see below.
    private static final int PASS_THROUGH_PERCENT = 10;
    private static final int MIXED_CURRENCIES_PERCENT = 20;
    private static final int LONGEST_ENTRY_PERCENT = 2;

    // Edge cases, in percent of the cases they apply to, so each rule is tried at its exact boundary and not only
    // with amounts far from it.
    private static final int WHOLE_BALANCE_PERCENT = 10;
    private static final int ONE_CENT_SHORT_PERCENT = 50;
    private static final int NET_OVERDRAFT_PERCENT = 25;
    private static final int SINGLE_LINE_PERCENT = 25;
    private static final int ACROSS_CURRENCIES_PERCENT = 25;

    private final Random random;

    // The steps a retry may send again. Steps count every operation next() has returned, retries included, as the
    // test numbers its requests; retries themselves are left out, as they only repeat another step.
    private final List<Integer> retryable = new ArrayList<>();

    // The posting steps whose key a ReuseKey may take. A ReuseKey step is left out, as its key is another step's.
    private final List<Integer> postings = new ArrayList<>();

    private int step;

    OperationGenerator(long seed) {
        this.random = new Random(seed);
    }

    /** The next operation, chosen from the model as it is after every earlier operation was applied to it. */
    Operation next(LedgerModel model) {
        Operation operation = choose(model.accounts(), model.entries());
        if (!(operation instanceof Operation.Retry)) {
            retryable.add(step);
        }
        if (operation instanceof Operation.Transfer || operation instanceof Operation.Entry
                || operation instanceof Operation.Reverse) {
            postings.add(step);
        }
        step++;
        return operation;
    }

    private Operation choose(List<ModelAccount> accounts, List<ModelEntry> entries) {
        int roll = random.nextInt(100);
        if (roll < OPEN_ACCOUNT_BELOW) {
            return openAccount(accounts);
        }
        if (roll < TRANSFER_BELOW) {
            return transferOrOpenAccount(accounts);
        }
        if (roll < ENTRY_BELOW) {
            return entryOrOpenAccount(accounts);
        }
        if (roll < REVERSE_BELOW) {
            return reverseOrTransfer(accounts, entries);
        }
        return retryOrTransfer(accounts);
    }

    private Operation.OpenAccount openAccount(List<ModelAccount> accounts) {
        String currency = pick(CURRENCIES);
        // Money enters a currency only when an account that may go negative pays it out, so each currency's first
        // account may; without one, every payment in that currency would be refused.
        boolean allowNegative = indexesIn(currency, accounts).isEmpty() || random.nextInt(ALLOW_NEGATIVE_ONE_IN) == 0;
        return new Operation.OpenAccount(currency, allowNegative);
    }

    // A transfer needs two accounts in one currency; until a currency has them, the step opens an account instead.
    private Operation transferOrOpenAccount(List<ModelAccount> accounts) {
        List<String> paired = pairedCurrencies(accounts);
        return paired.isEmpty() ? openAccount(accounts) : transfer(accounts, paired);
    }

    // An entry needs two accounts in one currency too, and opens an account instead until a currency has them.
    private Operation entryOrOpenAccount(List<ModelAccount> accounts) {
        List<String> paired = pairedCurrencies(accounts);
        return paired.isEmpty() ? openAccount(accounts) : entry(accounts, paired);
    }

    // With no entry yet there is nothing to reverse, so the step is a transfer, or opens an account until one can be.
    private Operation reverseOrTransfer(List<ModelAccount> accounts, List<ModelEntry> entries) {
        return entries.isEmpty() ? transferOrOpenAccount(accounts) : reverse(entries);
    }

    // Only the first step has no earlier request to send again; it is a transfer, or opens an account instead. A
    // posting step implies two accounts in one currency, so the new transfer of a reused key can always be made.
    private Operation retryOrTransfer(List<ModelAccount> accounts) {
        if (!postings.isEmpty() && chance(REUSED_KEY_PERCENT)) {
            return new Operation.ReuseKey(pick(postings), transfer(accounts, pairedCurrencies(accounts)));
        }
        return retryable.isEmpty() ? transferOrOpenAccount(accounts) : new Operation.Retry(pick(retryable));
    }

    private Operation.Transfer transfer(List<ModelAccount> accounts, List<String> paired) {
        if (chance(UNKNOWN_ACCOUNT_PERCENT)) {
            return transferWithUnknownAccount(accounts);
        }
        if (chance(WRONG_CURRENCY_PERCENT)) {
            return transferInWrongCurrency(accounts);
        }
        // Until a currency with two accounts has one that may not go negative, nothing can be overdrawn, and the
        // transfer is an ordinary one.
        List<Integer> overdrawable = nonNegativeIn(paired, accounts);
        if (!overdrawable.isEmpty() && chance(OVERDRAFT_PERCENT)) {
            return overdraft(pick(overdrawable), accounts);
        }
        String currency = pick(paired);
        Leg leg = payableLeg(currency, accounts, balancesOf(accounts));
        return new Operation.Transfer(leg.from(), leg.to(), leg.amount(), currency);
    }

    // The known side may lack the funds too: the API looks accounts up before it checks funds, so this is a 404.
    private Operation.Transfer transferWithUnknownAccount(List<ModelAccount> accounts) {
        int known = random.nextInt(accounts.size());
        long amount = 1 + random.nextLong(MAX_AMOUNT);
        String currency = accounts.get(known).currency();
        return random.nextBoolean()
                ? new Operation.Transfer(Operation.UNKNOWN, known, amount, currency)
                : new Operation.Transfer(known, Operation.UNKNOWN, amount, currency);
    }

    // Between accounts in two currencies the transfer is in one of them; within one currency it is in the other.
    // Either way at least one of the two accounts does not hold it.
    private Operation.Transfer transferInWrongCurrency(List<ModelAccount> accounts) {
        List<Integer> all = IntStream.range(0, accounts.size()).boxed().toList();
        int from = pick(all);
        int to = pickOther(all, from);
        String fromCurrency = accounts.get(from).currency();
        String toCurrency = accounts.get(to).currency();
        String currency = fromCurrency.equals(toCurrency)
                ? otherCurrency(fromCurrency)
                : pick(List.of(fromCurrency, toCurrency));
        return new Operation.Transfer(from, to, 1 + random.nextLong(MAX_AMOUNT), currency);
    }

    // Half the overdrafts are short by exactly one cent, the edge the check must still refuse.
    private Operation.Transfer overdraft(int from, List<ModelAccount> accounts) {
        ModelAccount payer = accounts.get(from);
        int to = pickOther(indexesIn(payer.currency(), accounts), from);
        long shortfall = chance(ONE_CENT_SHORT_PERCENT) ? 1 : 1 + random.nextLong(MAX_AMOUNT);
        return new Operation.Transfer(from, to, Math.addExact(payer.balance(), shortfall), payer.currency());
    }

    private Operation.Entry entry(List<ModelAccount> accounts, List<String> paired) {
        if (chance(UNKNOWN_LINE_PERCENT)) {
            return new Operation.Entry(withUnknownAccount(balancedLines(accounts, paired, fewLegs())));
        }
        if (chance(UNBALANCED_PERCENT)) {
            return new Operation.Entry(unbalancedLines(accounts, paired));
        }
        if (chance(WRONG_CURRENCY_LEG_PERCENT)) {
            return new Operation.Entry(withWrongCurrencyLeg(balancedLines(accounts, paired, fewLegs()), accounts,
                    paired));
        }
        // Like an overdraft, a pass-through needs an account that may not go negative; until there is one, the
        // entry is an ordinary one.
        List<Integer> passable = nonNegativeIn(paired, accounts);
        if (!passable.isEmpty() && chance(PASS_THROUGH_PERCENT)) {
            return new Operation.Entry(passThroughLines(pick(passable), accounts));
        }
        // Each leg is two lines, so the longest entry has exactly the most lines the API accepts.
        int legs = chance(LONGEST_ENTRY_PERCENT) ? MAX_LINES / 2 : fewLegs();
        return new Operation.Entry(balancedLines(accounts, paired, legs));
    }

    /**
     * Lines that sum to zero in each currency, made of legs that each move an amount from one account to another
     * in one currency. Each payer can afford its leg after the legs before it, so the entry is accepted.
     */
    private List<Operation.Line> balancedLines(List<ModelAccount> accounts, List<String> paired, int legs) {
        // A mixed entry alternates currencies leg by leg, so one of two legs or more has lines in both.
        boolean mixed = paired.size() > 1 && chance(MIXED_CURRENCIES_PERCENT);
        String only = pick(paired);
        long[] spendable = balancesOf(accounts);
        List<Operation.Line> lines = new ArrayList<>();
        for (int i = 0; i < legs; i++) {
            String currency = mixed ? paired.get(i % paired.size()) : only;
            Leg leg = payableLeg(currency, accounts, spendable);
            lines.add(new Operation.Line(leg.from(), Math.negateExact(leg.amount()), currency));
            lines.add(new Operation.Line(leg.to(), leg.amount(), currency));
        }
        // The ledger judges an entry by its net change per account, so a payment may come before the line that
        // funds it.
        Collections.shuffle(lines, random);
        return lines;
    }

    private List<Operation.Line> withUnknownAccount(List<Operation.Line> lines) {
        int unknown = random.nextInt(lines.size());
        Operation.Line line = lines.get(unknown);
        lines.set(unknown, new Operation.Line(Operation.UNKNOWN, line.amount(), line.currency()));
        return lines;
    }

    /**
     * Lines that do not sum to zero in some currency. Some are a single line, the smallest entry the API accepts,
     * which can never balance; some move an amount from one currency to the other, so they sum to zero overall but
     * not per currency; the rest grow one line of a balanced entry.
     */
    private List<Operation.Line> unbalancedLines(List<ModelAccount> accounts, List<String> paired) {
        List<Operation.Line> lines = balancedLines(accounts, paired, fewLegs());
        if (chance(SINGLE_LINE_PERCENT)) {
            return List.of(lines.getFirst());
        }
        if (paired.size() > 1 && chance(ACROSS_CURRENCIES_PERCENT)) {
            return withLegAcrossCurrencies(lines, accounts, paired);
        }
        return withOneLineGrown(lines);
    }

    private List<Operation.Line> withLegAcrossCurrencies(List<Operation.Line> lines, List<ModelAccount> accounts,
            List<String> paired) {
        String paidIn = paired.getFirst();
        String receivedIn = paired.getLast();
        long amount = 1 + random.nextLong(MAX_AMOUNT);
        lines.add(new Operation.Line(pick(indexesIn(paidIn, accounts)), Math.negateExact(amount), paidIn));
        lines.add(new Operation.Line(pick(indexesIn(receivedIn, accounts)), amount, receivedIn));
        Collections.shuffle(lines, random);
        return lines;
    }

    // Growing the amount away from zero keeps its sign, so the line stays non-zero as the API requires.
    private List<Operation.Line> withOneLineGrown(List<Operation.Line> lines) {
        int grown = random.nextInt(lines.size());
        Operation.Line line = lines.get(grown);
        long growth = 1 + random.nextLong(MAX_AMOUNT);
        long amount = line.amount() > 0
                ? Math.addExact(line.amount(), growth)
                : Math.subtractExact(line.amount(), growth);
        lines.set(grown, new Operation.Line(line.account(), amount, line.currency()));
        return lines;
    }

    // A leg between two accounts of one currency, in the other currency: every currency still sums to zero, so
    // only the check that each account holds its line's currency refuses the entry.
    private List<Operation.Line> withWrongCurrencyLeg(List<Operation.Line> lines, List<ModelAccount> accounts,
            List<String> paired) {
        String held = pick(paired);
        List<Integer> holders = indexesIn(held, accounts);
        int from = pick(holders);
        int to = pickOther(holders, from);
        long amount = 1 + random.nextLong(MAX_AMOUNT);
        String wrong = otherCurrency(held);
        lines.add(new Operation.Line(from, Math.negateExact(amount), wrong));
        lines.add(new Operation.Line(to, amount, wrong));
        Collections.shuffle(lines, random);
        return lines;
    }

    /**
     * Pays an account that may not go negative and has it pass on more than it held before the entry, but no more
     * than it holds once paid, so the entry is accepted only by a ledger that judges the net change per account.
     * Some pass on one cent more than that, so the net change itself is an overdraft. The account is named on two
     * lines, and the receiver may be the payer.
     */
    private List<Operation.Line> passThroughLines(int through, List<ModelAccount> accounts) {
        ModelAccount middle = accounts.get(through);
        String currency = middle.currency();
        List<Integer> others = without(indexesIn(currency, accounts), through);
        // The currency's first account may go negative and is not this one, so there is always a payer.
        int payer = pick(payers(others, accounts, balancesOf(accounts)));
        long paidIn = affordable(accounts.get(payer), accounts.get(payer).balance());
        long held = middle.balance();
        long passedOn = chance(NET_OVERDRAFT_PERCENT)
                ? Math.addExact(Math.addExact(held, paidIn), 1)
                : Math.addExact(held, 1 + random.nextLong(paidIn));
        List<Operation.Line> lines = new ArrayList<>(List.of(
                new Operation.Line(payer, Math.negateExact(paidIn), currency),
                new Operation.Line(through, paidIn, currency),
                new Operation.Line(through, Math.negateExact(passedOn), currency),
                new Operation.Line(pick(others), passedOn, currency)));
        Collections.shuffle(lines, random);
        return lines;
    }

    // When every entry has been reversed, a second reversal is the only one left to try.
    private Operation.Reverse reverse(List<ModelEntry> entries) {
        if (chance(UNKNOWN_ENTRY_PERCENT)) {
            return new Operation.Reverse(Operation.UNKNOWN);
        }
        Map<Boolean, List<Integer>> byReversed = IntStream.range(0, entries.size()).boxed()
                .collect(Collectors.partitioningBy(index -> entries.get(index).reversed()));
        List<Integer> reversed = byReversed.get(true);
        List<Integer> notReversed = byReversed.get(false);
        if (!reversed.isEmpty() && (notReversed.isEmpty() || chance(SECOND_REVERSAL_PERCENT))) {
            return new Operation.Reverse(pick(reversed));
        }
        return new Operation.Reverse(pick(notReversed));
    }

    /**
     * A payment the ledger accepts, from an account that can afford it to another account of the currency.
     * spendable starts as the balances and follows each leg, so a later leg of the same entry may spend what an
     * earlier one paid in. Each currency's first account may go negative, so there is always a payer.
     */
    private Leg payableLeg(String currency, List<ModelAccount> accounts, long[] spendable) {
        List<Integer> holders = indexesIn(currency, accounts);
        int from = pick(payers(holders, accounts, spendable));
        int to = pickOther(holders, from);
        long amount = affordable(accounts.get(from), spendable[from]);
        spendable[from] = Math.subtractExact(spendable[from], amount);
        spendable[to] = Math.addExact(spendable[to], amount);
        return new Leg(from, to, amount);
    }

    // An account that may go negative can pay any amount; any other pays at most what it holds, and now and then
    // exactly that, the edge the overdraft check must still accept.
    private long affordable(ModelAccount payer, long spendable) {
        if (payer.allowNegative()) {
            return 1 + random.nextLong(MAX_AMOUNT);
        }
        if (chance(WHOLE_BALANCE_PERCENT)) {
            return spendable;
        }
        return 1 + random.nextLong(Math.min(spendable, MAX_AMOUNT));
    }

    private int fewLegs() {
        return 1 + random.nextInt(MAX_LEGS);
    }

    private boolean chance(int percent) {
        return random.nextInt(100) < percent;
    }

    private <T> T pick(List<T> choices) {
        return choices.get(random.nextInt(choices.size()));
    }

    private int pickOther(List<Integer> indexes, int excluded) {
        return pick(without(indexes, excluded));
    }

    private static List<Integer> without(List<Integer> indexes, int excluded) {
        return indexes.stream().filter(index -> index != excluded).toList();
    }

    private static List<Integer> payers(List<Integer> candidates, List<ModelAccount> accounts, long[] spendable) {
        return candidates.stream()
                .filter(index -> accounts.get(index).allowNegative() || spendable[index] > 0)
                .toList();
    }

    // Currencies with the two accounts a transfer or an entry needs, in a fixed order so the run stays replayable.
    private static List<String> pairedCurrencies(List<ModelAccount> accounts) {
        return CURRENCIES.stream().filter(currency -> indexesIn(currency, accounts).size() >= 2).toList();
    }

    private static List<Integer> nonNegativeIn(List<String> currencies, List<ModelAccount> accounts) {
        return IntStream.range(0, accounts.size())
                .filter(index -> !accounts.get(index).allowNegative()
                        && currencies.contains(accounts.get(index).currency()))
                .boxed()
                .toList();
    }

    private static List<Integer> indexesIn(String currency, List<ModelAccount> accounts) {
        return IntStream.range(0, accounts.size())
                .filter(index -> accounts.get(index).currency().equals(currency))
                .boxed()
                .toList();
    }

    private static long[] balancesOf(List<ModelAccount> accounts) {
        return accounts.stream().mapToLong(ModelAccount::balance).toArray();
    }

    private static String otherCurrency(String currency) {
        return CURRENCIES.stream().filter(other -> !other.equals(currency)).findFirst().orElseThrow();
    }

    /** One payment of an entry or a transfer: amount from one account to another, two lines in an entry. */
    private record Leg(int from, int to, long amount) {
    }

}
