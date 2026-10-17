package dev.jenishsoni.tally.common;

/** The request clashes with existing state, such as a duplicate code; answered as 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

}
