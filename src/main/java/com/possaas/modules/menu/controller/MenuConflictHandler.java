package com.possaas.modules.menu.controller;

import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import com.possaas.modules.menu.service.MenuConflictTranslator;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = { MenuGroupController.class, MenuItemController.class })
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class MenuConflictHandler {
    private final Clock clock;

    @ExceptionHandler({ DataIntegrityViolationException.class, OptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class })
    public ResponseEntity<ErrorResponse> conflict(RuntimeException failure) {
        var error = MenuConflictTranslator.translate(failure);
        return ResponseEntity.status(error.getStatus())
                .body(new ErrorResponse(false, error.getCode(), error.getMessage(), Map.of(), clock.instant()));
    }
}
