package dev.jenishsoni.tally.idempotency;

/** What {@link IdempotencyStore#claim} found for a key. */
public sealed interface Claim {

    /** First use of the key: the caller does the work and then saves its response. */
    record Claimed() implements Claim {
    }

    /** The same request was already completed; answer with the saved response. */
    record Replay(int status, String body) implements Claim {
    }

    /** The key was first used with a different request. */
    record Conflict() implements Claim {
    }

}
