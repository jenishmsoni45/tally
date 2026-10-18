package dev.jenishsoni.tally.posting;

import dev.jenishsoni.tally.common.UnprocessableException;

/** An entry's lines do not sum to zero in every currency; answered as 422. */
public class UnbalancedEntryException extends UnprocessableException {

    public UnbalancedEntryException(String message) {
        super(message);
    }

}
