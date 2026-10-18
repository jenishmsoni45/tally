package dev.jenishsoni.tally.posting;

import dev.jenishsoni.tally.common.UnprocessableException;

/** A posting would take an account that disallows negative balances below zero; answered as 422. */
public class InsufficientFundsException extends UnprocessableException {

    public InsufficientFundsException(String message) {
        super(message);
    }

}
