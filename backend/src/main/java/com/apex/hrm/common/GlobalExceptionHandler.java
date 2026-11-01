package com.apex.hrm.common;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(Instant timestamp, int status, String error, String message) {
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> conflict(OptimisticLockingFailureException e) {
        return build(HttpStatus.CONFLICT, "The record was changed by someone else; reload and retry");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return build(HttpStatus.CONFLICT, "Constraint violation (duplicate, missing reference, or required field)");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e) {
        log.error("Unhandled error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }
}
