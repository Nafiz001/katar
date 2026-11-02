package dev.nafiz.katar.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.nafiz.katar.config.KatarException;

/**
 * Every error has one shape, {"error": {"code", "message"}}. Clients branch on
 * the code, which never changes; the message is for people.
 */
@RestControllerAdvice
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(KatarException.class)
    ResponseEntity<Map<String, Object>> katar(KatarException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "bad_request", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).findFirst().orElse("invalid request");
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "bad_request", msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", "the request body is not valid JSON for this endpoint");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException e) {
        return body(HttpStatus.BAD_REQUEST, "missing_header", "send the " + e.getHeaderName() + " header");
    }

    static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        if (status.is5xxServerError()) {
            log.error("{}: {}", code, message);
        }
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
