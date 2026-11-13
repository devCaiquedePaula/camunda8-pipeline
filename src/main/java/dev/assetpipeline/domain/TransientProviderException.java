package dev.assetpipeline.domain;

/** Provider failure worth retrying: network timeout, HTTP 429/5xx, rate-limit note. */
public class TransientProviderException extends RuntimeException {

    public TransientProviderException(String message) {
        super(message);
    }

    public TransientProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
