package dev.jenishsoni.tally.posting;

/** A posting's response: the status and entry saved the first time, and replayed on every retry of the same key. */
public record PostingResult(int status, JournalEntry entry) {
}
