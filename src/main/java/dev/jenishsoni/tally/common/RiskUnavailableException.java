package dev.jenishsoni.tally.common;

/**
 * The risk check gave no decision and the backup answer refused the transfer; answered as 503, because nothing was
 * decided and a retry with the same key asks the risk check again.
 */
public class RiskUnavailableException extends RuntimeException {

    public RiskUnavailableException() {
        super("The risk check gave no decision and the backup answer refused the transfer");
    }

}
