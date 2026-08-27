package dev.nytweetdeck.xapi.http;

public class XApiHttpException extends RuntimeException {

    private final int statusCode;
    private final Long retryAfterSeconds;

    public XApiHttpException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public XApiHttpException(String message, int statusCode, Long retryAfterSeconds) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public XApiHttpException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.retryAfterSeconds = null;
    }

    public int statusCode() {
        return statusCode;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
