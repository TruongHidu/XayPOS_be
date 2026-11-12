package com.possaas.modules.subscriptionpurchase.controller;

import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.ClosePurchaseRequest;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PurchaseDetail;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PurchaseSummary;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.service.AdminPurchaseQueryService;
import com.possaas.modules.subscriptionpurchase.service.AdminPurchaseReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Super admin: package purchases of every restaurant and decisions on purchases waiting for review. */
@Validated
@RestController
@RequestMapping("/api/v1/admin/subscription-purchases")
@RequiredArgsConstructor
public class AdminPurchaseController {
    private final AdminPurchaseQueryService queries;
    private final AdminPurchaseReviewService reviews;
    private final CurrentUserProvider currentUser;

    @GetMapping
    @PreAuthorize("@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_VIEW')")
    ResponseEntity<PageResponse<PurchaseSummary>> search(
        @RequestParam(required = false) PurchaseStatus status,
        @RequestParam(required = false) UUID restaurantId,
        @RequestParam(required = false) @Size(max = 100) String q,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return noStore(queries.search(status, restaurantId, q, page, size));
    }

    @GetMapping("/{purchaseId}")
    @PreAuthorize("@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_VIEW')")
    ResponseEntity<PurchaseDetail> detail(@PathVariable UUID purchaseId) {
        return noStore(queries.detail(purchaseId));
    }

    @PostMapping("/{purchaseId}/close")
    @PreAuthorize("@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_PAYMENT_REVIEW')")
    ResponseEntity<PurchaseDetail> close(@PathVariable UUID purchaseId, @Valid @RequestBody ClosePurchaseRequest request,
            HttpServletRequest http) {
        return noStore(reviews.close(purchaseId, request, currentUser.getRequired().userId(), http.getRemoteAddr()));
    }

    @PostMapping("/{purchaseId}/resume-activation")
    @PreAuthorize("@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_PAYMENT_REVIEW')")
    ResponseEntity<PurchaseDetail> resumeActivation(@PathVariable UUID purchaseId, HttpServletRequest http) {
        return noStore(reviews.resumeActivation(purchaseId, currentUser.getRequired().userId(), http.getRemoteAddr()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
