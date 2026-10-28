package dev.jenishsoni.tally.risk;

import java.util.Arrays;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

/** The risk engine's answer for a transfer, and the backup answer when it gives none (docs/risk-decision-call.md). */
public enum Decision {

    ALLOW("allow"),
    HOLD("hold"),
    BLOCK("block");

    private final String wireName;

    Decision(String wireName) {
        this.wireName = wireName;
    }

    /** The lowercase form the contract uses on the wire, which is also the decision's outcome tag. */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    // Exact match only: the contract allows these three lowercase values, so "ALLOW" or "allow " is no decision.
    static Optional<Decision> fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(decision -> decision.wireName.equals(wireName))
                .findFirst();
    }

}
