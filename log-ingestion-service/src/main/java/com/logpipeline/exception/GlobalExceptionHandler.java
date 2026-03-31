package com.logpipeline.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Centralised exception handling for all REST endpoints.
 *
 * Ensures no raw stack traces leak to API consumers — every error
 * returns a structured JSON body with enough context to diagnose
 * without exposing internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles @Valid failures on @RequestBody — field-level validation errors.
     * Triggered when a LogEventRequest fails @NotBlank, @Pattern etc.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        List<String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();

        log.warn("Validation failed: {}", errors);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "status", 400,
                        "error", "Validation failed",
                        "details", errors,
                        "timestamp", Instant.now().toString()
                ));
    }

    /**
     * Handles @Valid failures on @RequestParam / @PathVariable level.
     * Triggered by @Validated + @Size on the controller method itself.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolations(
            ConstraintViolationException ex) {

        List<String> errors = ex.getConstraintViolations()
                .stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();

        log.warn("Constraint violation: {}", errors);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "status", 400,
                        "error", "Validation failed",
                        "details", errors,
                        "timestamp", Instant.now().toString()
                ));
    }

    /**
     * Handles malformed JSON — missing required fields, wrong types, unparseable values.
     * Without this, Spring returns a 400 with a raw HttpMessageNotReadableException trace.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(
            HttpMessageNotReadableException ex) {

        log.warn("Malformed request body: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "status", 400,
                        "error", "Malformed request body",
                        "details", List.of("Check JSON syntax and field types"),
                        "timestamp", Instant.now().toString()
                ));
    }

    /**
     * Catch-all for any unexpected exception.
     * Logs the full stack trace internally but returns a safe generic message externally.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {

        log.error("Unexpected error processing request", ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "status", 500,
                        "error", "Internal server error",
                        "details", List.of("An unexpected error occurred"),
                        "timestamp", Instant.now().toString()
                ));
    }
}