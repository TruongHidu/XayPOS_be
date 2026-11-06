package com.possaas.modules.cashbook.controller;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;
@Slf4j @Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=CashbookController.class) @RequiredArgsConstructor
public class CashbookErrorHandler {
    private final Clock clock;
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException ex) {return error(ex.getStatus(),ex.getCode(),ex.getMessage());}
    @ExceptionHandler({BindException.class,ConstraintViolationException.class,MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,HandlerMethodValidationException.class})
    ResponseEntity<ErrorResponse> validation(Exception ex) {return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Request validation failed");}
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> body(Exception ex) {return error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_BODY","Request body is malformed");}
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(Exception ex) {return error(HttpStatus.FORBIDDEN,"FORBIDDEN","Required cashbook permission is missing");}
    @ExceptionHandler({DataIntegrityViolationException.class,ObjectOptimisticLockingFailureException.class,OptimisticLockException.class})
    ResponseEntity<ErrorResponse> conflict(Exception ex) {return error(HttpStatus.CONFLICT,"CONCURRENT_CASHBOOK_UPDATE","Cashbook state changed or violates a database constraint");}
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ex) {log.error("Cashbook operation failed",ex);return error(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","Could not process cashbook operation");}
    private ResponseEntity<ErrorResponse> error(HttpStatus status,String code,String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(false,code,message,Map.of(),clock.instant()));
    }
}
