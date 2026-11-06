package com.possaas.modules.subscriptionpurchase.controller;

import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseRequests;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseResponses;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.service.PurchaseCommandService;
import com.possaas.modules.subscriptionpurchase.service.PurchaseQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/subscription-purchases") @RequiredArgsConstructor @Validated
public class OwnerPurchaseController {
    private final PurchaseCommandService commands;
    private final PurchaseQueryService queries;

    @PostMapping
    @PreAuthorize("hasRole('OWNER') and hasAuthority('SUBSCRIPTION_PURCHASE_CREATE')")
    public ResponseEntity<PurchaseResponses.View> create(@Valid @RequestBody PurchaseRequests.Create request,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        var result = commands.create(request, key, http.getRemoteAddr());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(result.body());
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER') and hasAuthority('SUBSCRIPTION_PURCHASE_VIEW')")
    public PageResponse<PurchaseResponses.View> list(@RequestParam(required = false) PurchaseStatus status,
        @RequestParam(required = false) String createdFrom, @RequestParam(required = false) String createdTo,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.search(status, createdFrom, createdTo, page, size);
    }

    @GetMapping("/{purchaseId}")
    @PreAuthorize("hasRole('OWNER') and hasAuthority('SUBSCRIPTION_PURCHASE_VIEW')")
    public PurchaseResponses.View detail(@PathVariable UUID purchaseId) { return queries.detail(purchaseId); }

    @PostMapping("/{purchaseId}/cancel")
    @PreAuthorize("hasRole('OWNER') and hasAuthority('SUBSCRIPTION_PURCHASE_CANCEL')")
    public PurchaseResponses.View cancel(@PathVariable UUID purchaseId,
        @Valid @RequestBody PurchaseRequests.Cancel request, HttpServletRequest http) {
        return commands.cancel(purchaseId, request, http.getRemoteAddr());
    }
}
