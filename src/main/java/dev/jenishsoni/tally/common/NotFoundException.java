package dev.jenishsoni.tally.common;

/** The requested resource does not exist; answered as 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

}
