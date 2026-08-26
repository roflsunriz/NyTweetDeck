package dev.nytweetdeck.xapi.http;

public class XApiHttpException extends RuntimeException {

    private final int statusCode;

    public XApiHttpException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public XApiHttpException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int statusCode() {
        return statusCode;
    }
}
