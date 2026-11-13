package dev.assetpipeline.domain;

/** Provider failure that retrying cannot fix: invalid API key, unknown symbol. */
public class BusinessProviderException extends RuntimeException {

    public BusinessProviderException(String message) {
        super(message);
    }

    public BusinessProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
