package dev.jenishsoni.tally.posting;

/**
 * A posting's response: the status and entry saved the first time, and replayed on every retry of the same key.
 * Replayed is true when this is the saved response of an earlier request with the same key.
 */
public record PostingResult(int status, JournalEntry entry, boolean replayed) {
}
