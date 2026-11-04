package com.possaas.modules.subscriptionpurchase.controller;

import com.possaas.modules.subscriptionpurchase.dto.PaymentAttemptResponses;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseRequests;
import com.possaas.modules.subscriptionpurchase.service.PaymentAttemptCommandService;
import com.possaas.modules.subscriptionpurchase.service.PaymentAttemptQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/subscription-purchases/{purchaseId}/payment-attempts") @RequiredArgsConstructor
public class PurchasePaymentAttemptController {
    private final PaymentAttemptCommandService commands;
    private final PaymentAttemptQueryService queries;

    @PostMapping
    @PreAuthorize("hasRole('OWNER') and hasAuthority('SUBSCRIPTION_PURCHASE_CREATE')")
    public ResponseEntity<PaymentAttemptResponses.View> create(@PathVariable UUID purchaseId,
        @RequestBody PurchaseRequests.CreateAttempt request,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        var result = commands.create(purchaseId, key, http.getRemoteAddr());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(result.body());
    }

    @GetMapping("/current")
    @PreAuthorize("hasRole('OWNER') and hasAuthority('SUBSCRIPTION_PURCHASE_VIEW')")
    public PaymentAttemptResponses.Current current(@PathVariable UUID purchaseId) { return queries.current(purchaseId); }
}
