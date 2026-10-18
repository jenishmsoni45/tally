package dev.jenishsoni.tally.posting;

/**
 * Posts balanced journal entries. Each call is one database transaction that follows this
 * recipe, and nothing else writes journal rows or balances:
 * <ol>
 * <li>validate the request;</li>
 * <li>claim the idempotency key (from Phase 3);</li>
 * <li>lock the touched balance rows with SELECT ... FOR UPDATE in ascending account_id order;</li>
 * <li>check that no account that disallows negative balances would go below zero;</li>
 * <li>insert the entry and its lines;</li>
 * <li>update the balances;</li>
 * <li>insert the outbox row (from Phase 4);</li>
 * <li>save the response for idempotent replay (from Phase 3);</li>
 * <li>commit.</li>
 * </ol>
 * Throws {@link UnbalancedEntryException} or {@link InsufficientFundsException} (both 422), and
 * NotFoundException for an unknown account.
 */
public interface PostingService {

    JournalEntry postTransfer(TransferRequest request);

    JournalEntry postEntry(EntryRequest request);

}
