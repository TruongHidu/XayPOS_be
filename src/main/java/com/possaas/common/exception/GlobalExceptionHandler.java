package com.possaas.common.exception;

import java.time.Instant;
import java.util.Map;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    public record ErrorResponse(boolean success, String code, String message, Map<String, String> fieldErrors, Instant timestamp) {}

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ErrorResponse(false, ex.getCode(), ex.getMessage(), Map.of(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
            .collect(java.util.stream.Collectors.toMap(FieldError::getField, e -> e.getDefaultMessage() == null ? "Invalid value" : e.getDefaultMessage(), (a, b) -> a));
        return ResponseEntity.badRequest().body(new ErrorResponse(false, "VALIDATION_ERROR", "Request validation failed", errors, Instant.now()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> constraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(false, "VALIDATION_ERROR", "Request validation failed", Map.of(), Instant.now()));
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        HandlerMethodValidationException.class
    })
    ResponseEntity<ErrorResponse> invalidRequestParameter(Exception ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
            false,
            "VALIDATION_ERROR",
            "Request validation failed",
            Map.of(),
            Instant.now()
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
            false,
            "INVALID_REQUEST_BODY",
            "Request body is malformed or contains unsupported fields",
            Map.of(),
            Instant.now()
        ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(405).body(new ErrorResponse(
            false,
            "METHOD_NOT_ALLOWED",
            "HTTP method is not supported for this endpoint",
            Map.of(),
            Instant.now()
        ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> dataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(409).body(new ErrorResponse(false, "CONFLICT", "Resource already exists or violates a constraint", Map.of(), Instant.now()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> accessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(new ErrorResponse(false, "FORBIDDEN", "Access denied", Map.of(), Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ex) {
        return ResponseEntity.internalServerError().body(new ErrorResponse(false, "INTERNAL_ERROR", "Unexpected server error", Map.of(), Instant.now()));
    }
}
