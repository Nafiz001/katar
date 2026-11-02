package dev.nafiz.katar.config;

import org.springframework.http.HttpStatus;

/**
 * A refusal the client should understand: an HTTP status, and a stable
 * machine-readable code that clients branch on (never the message).
 */
public class KatarException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public KatarException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static KatarException notFound(String what) {
        return new KatarException(HttpStatus.NOT_FOUND, "not_found", what + " does not exist");
    }

    public static KatarException conflict(String code, String message) {
        return new KatarException(HttpStatus.CONFLICT, code, message);
    }

    public static KatarException forbidden(String code, String message) {
        return new KatarException(HttpStatus.FORBIDDEN, code, message);
    }

    public static KatarException unprocessable(String code, String message) {
        return new KatarException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
