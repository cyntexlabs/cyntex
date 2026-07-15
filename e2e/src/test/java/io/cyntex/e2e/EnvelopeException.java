package io.cyntex.e2e;

/**
 * A test specification could not be read. Deliberately uncoded: the product's error-code catalog is
 * an external contract owned by the shipped product, and this harness never ships to a user of it.
 * The audience here is whoever authored the specification, and the message names the offending
 * token so a failing build says what to fix.
 */
public class EnvelopeException extends RuntimeException {

    public EnvelopeException(String message) {
        super(message);
    }

    public EnvelopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
