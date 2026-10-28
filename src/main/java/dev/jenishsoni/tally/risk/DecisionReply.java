package dev.jenishsoni.tally.risk;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The fields tally reads from the risk engine's 200 (docs/risk-decision-call.md, 3.2). ruleSet and fired are not
 * mapped at all, since tally must not store, log or return them. The fields are strings so that the contract's
 * rules are checked here, exactly, rather than left to the JSON mapper's leniency with UUIDs and enums.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record DecisionReply(String decisionId, String decision, String onTimeout) {

    // The canonical 8-4-4-4-12 form. UUID.fromString also takes short forms such as 1-2-3-4-5 and would store
    // them spelled differently from the risk engine's id, which breaks its join on decisionId.
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");

    /** The decision this reply carries, or empty when any of the three fields breaks the contract. */
    Optional<RiskResult.Decided> toDecided() {
        Optional<Decision> decided = Decision.fromWireName(decision);
        Optional<Decision> backup = Decision.fromWireName(onTimeout);
        if (!isCanonicalUuid(decisionId) || decided.isEmpty() || backup.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RiskResult.Decided(UUID.fromString(decisionId), decided.get(), backup.get()));
    }

    private static boolean isCanonicalUuid(String value) {
        return value != null && CANONICAL_UUID.matcher(value).matches();
    }

}
