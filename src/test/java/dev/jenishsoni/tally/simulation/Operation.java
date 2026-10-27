package dev.jenishsoni.tally.simulation;

import java.util.List;

/**
 * One step of a simulation run, as the generator chose it. Accounts and entries are named by their index in the
 * model, in the order they were created, so a run is replayed from its seed alone; the test maps each index to the
 * id the server gave it. Every operation is a request the API accepts as well formed: the refusals it can provoke
 * are the ledger's own (404, 409, 422), never a 400.
 */
sealed interface Operation {

    /** An account or entry index that matches nothing; the test sends an id no account or entry has. */
    int UNKNOWN = -1;

    record OpenAccount(String currency, boolean allowNegative) implements Operation {
    }

    /** Moves amount (positive) from one account to another. from and to always differ; either may be UNKNOWN. */
    record Transfer(int from, int to, long amount, String currency) implements Operation {
    }

    /**
     * A multi-line entry of 1 to 100 lines with non-zero amounts. The lines may fail to balance, name an UNKNOWN
     * account, or use a currency the account does not hold.
     */
    record Entry(List<Line> lines) implements Operation {

        // Public because a record nested in an interface is public, and its canonical constructor must be too.
        public Entry {
            lines = List.copyOf(lines);
        }

    }

    /** Reverses the entry at this index, which may be UNKNOWN, already reversed, or itself a reversal. */
    record Reverse(int entry) implements Operation {
    }

    /** Sends the request of an earlier step of the same run, never itself a Retry, again with the same key and body. */
    record Retry(int operation) implements Operation {
    }

    /**
     * Sends a new transfer with the idempotency key of an earlier posting step, as a client that reuses a key by
     * mistake. It is answered like any request under that key: a 409 if the key saved a different request, a replay
     * if it saved this same one, and a new posting if no posting under the key has succeeded yet.
     */
    record ReuseKey(int operation, Transfer request) implements Operation {
    }

    record Line(int account, long amount, String currency) {
    }

}
