package dev.jenishsoni.tally.balance;

import java.util.List;

/** One page of lines, oldest first. Pass nextCursor as the cursor of the next request; null means no more. */
public record AccountLinePage(List<AccountLine> items, Long nextCursor) {
}
