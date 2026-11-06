package com.possaas.modules.payment.controller;

import com.possaas.modules.payment.dto.PaymentResponse;
import com.possaas.modules.payment.dto.RecordManualPaymentRequest;
import com.possaas.modules.payment.service.PaymentCommandService;
import com.possaas.modules.payment.service.PaymentQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated @RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/orders/{orderId}/payments")
public class PaymentController {
    private final PaymentCommandService commands;
    private final PaymentQueryService queries;

    @PostMapping
    @PreAuthorize("hasAuthority('PAYMENT_CREATE') and hasAuthority('PAYMENT_CONFIRM')")
    public ResponseEntity<PaymentResponse> record(@PathVariable UUID orderId,
            @Valid @RequestBody RecordManualPaymentRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        var result = commands.recordManual(orderId, request, key, http.getRemoteAddr());
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .header("Idempotency-Replayed", Boolean.toString(result.replay()))
            .body(result.response());
    }

    @GetMapping @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    public ResponseEntity<PageResponse<PaymentResponse>> list(@PathVariable UUID orderId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(queries.list(orderId, page, size));
    }

    @GetMapping("/{paymentId}") @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    public ResponseEntity<PaymentResponse> detail(@PathVariable UUID orderId, @PathVariable UUID paymentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(queries.detail(orderId, paymentId));
    }
}
