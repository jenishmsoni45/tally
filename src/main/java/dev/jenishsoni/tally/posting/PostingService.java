package dev.jenishsoni.tally.posting;

import java.util.UUID;

/**
 * Posts balanced journal entries. Each call is one database transaction that follows this
 * recipe, and nothing else writes journal rows or balances:
 * <ol>
 * <li>validate the request;</li>
 * <li>claim the idempotency key; a retry of a completed request returns its saved response here;</li>
 * <li>lock the touched balance rows with SELECT ... FOR UPDATE in ascending account_id order;</li>
 * <li>check that no account that disallows negative balances would go below zero;</li>
 * <li>insert the entry and its lines;</li>
 * <li>update the balances;</li>
 * <li>insert the outbox row (from Phase 4);</li>
 * <li>save the response for idempotent replay;</li>
 * <li>commit.</li>
 * </ol>
 * Throws {@link UnbalancedEntryException} or {@link InsufficientFundsException} (both 422),
 * NotFoundException for an unknown account or entry, and ConflictException when the idempotency
 * key was first used for a different request or the entry was already reversed.
 */
public interface PostingService {

    PostingResult postTransfer(String idempotencyKey, TransferRequest request);

    PostingResult postEntry(String idempotencyKey, EntryRequest request);

    /** Posts the mirror image of an entry's lines, which undoes the entry's effect on every account. */
    PostingResult reverseEntry(String idempotencyKey, UUID entryId);

}
