package dev.jenishsoni.tally.common;

/** A well-formed request that a business rule refuses, such as an unbalanced entry; answered as 422. */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }

}
