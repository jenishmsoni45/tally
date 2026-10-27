package dev.jenishsoni.tally.common;

/** The request lacks valid credentials, such as the admin API key; answered as 401. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }

}
