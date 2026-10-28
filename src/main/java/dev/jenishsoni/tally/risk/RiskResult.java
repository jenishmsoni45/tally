package dev.jenishsoni.tally.risk;

import java.util.UUID;

/** What the risk engine answered for one transfer (docs/risk-decision-call.md, 3.2). */
public sealed interface RiskResult {

    /** The outcome tag of the ledger.risk.duration timer: allow, hold, block, conflict, timeout, unavailable or invalid. */
    String outcome();

    /** A valid 200: the decision, and the backup answer of the rule set that made it. */
    record Decided(UUID decisionId, Decision decision, Decision onTimeout) implements RiskResult {

        @Override
        public String outcome() {
            return decision.wireName();
        }

    }

    /** A 409: the risk engine already decided this requestId for a different transfer. */
    record Conflict() implements RiskResult {

        @Override
        public String outcome() {
            return "conflict";
        }

    }

    /** No decision, for the given reason; the caller uses the backup answer. */
    record NoDecision(Reason reason) implements RiskResult {

        @Override
        public String outcome() {
            return reason.tag();
        }

        public enum Reason {

            /** No answer within tally.risk.timeout, whichever step it was stuck in. */
            TIMEOUT("timeout"),

            /** The connection was refused or reset, or the status was neither 200 nor 409. */
            UNAVAILABLE("unavailable"),

            /** A 200 whose body doesn't parse or breaks the contract. */
            INVALID("invalid");

            private final String tag;

            Reason(String tag) {
                this.tag = tag;
            }

            public String tag() {
                return tag;
            }

        }

    }

}
