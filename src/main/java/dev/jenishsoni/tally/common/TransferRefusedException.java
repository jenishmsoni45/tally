package dev.jenishsoni.tally.common;

import java.util.UUID;

/**
 * The risk check held or blocked a transfer; answered as 403 with the decision's id, which lets support find the
 * decision without the response naming the rules that fired.
 */
public class TransferRefusedException extends RuntimeException {

    public enum Reason {
        HELD, BLOCKED
    }

    private final Reason reason;
    private final UUID decisionId;

    public TransferRefusedException(Reason reason, UUID decisionId) {
        super("Risk check " + reason.name().toLowerCase() + " the transfer in decision " + decisionId);
        this.reason = reason;
        this.decisionId = decisionId;
    }

    public Reason reason() {
        return reason;
    }

    public UUID decisionId() {
        return decisionId;
    }

}
