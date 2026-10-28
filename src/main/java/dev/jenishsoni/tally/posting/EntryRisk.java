package dev.jenishsoni.tally.posting;

import java.util.UUID;

/**
 * What the risk check decided for one entry, stored on it as risk_check and decision_id:
 * <ul>
 * <li>decided: the risk engine allowed the transfer, and decisionId is its decision;</li>
 * <li>fallback: the risk engine didn't answer and the backup answer was allow, so there is no decision;</li>
 * <li>none: the check is off, or the entry isn't a transfer (entries and reversals).</li>
 * </ul>
 */
public record EntryRisk(String riskCheck, UUID decisionId) {

    public static final EntryRisk NONE = new EntryRisk("none", null);

    public static final EntryRisk FALLBACK = new EntryRisk("fallback", null);

    // The same rule as V7's checks on journal_entries, so a wrong pair fails before any row is locked.
    public EntryRisk {
        boolean decided = switch (riskCheck) {
            case "decided" -> true;
            case "fallback", "none" -> false;
            case null, default -> throw new IllegalArgumentException("Unknown risk check: " + riskCheck);
        };
        if (decided != (decisionId != null)) {
            throw new IllegalArgumentException("A decision id goes with risk check decided, and only with it");
        }
    }

    public static EntryRisk decided(UUID decisionId) {
        return new EntryRisk("decided", decisionId);
    }

}
