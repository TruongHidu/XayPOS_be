package com.possaas.modules.subscription.controller;

import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.modules.subscription.dto.CurrentEntitlementResponse;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscription.dto.SubscriptionResponse;
import com.possaas.modules.subscription.service.SubscriptionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SubscriptionController {
    private final CurrentTenantProvider currentTenantProvider;
    private final SubscriptionQueryService subscriptionQueryService;

    @GetMapping("/api/v1/subscriptions/current")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_VIEW')")
    ResponseEntity<CurrentEntitlementResponse> current() {
        return ResponseEntity.ok(
            subscriptionQueryService.getCurrent(currentTenantProvider.getRequiredRestaurantId())
        );
    }

    @GetMapping("/api/v1/subscriptions/history")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_VIEW')")
    ResponseEntity<PageResponse<SubscriptionResponse>> history(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(
            Math.max(page, 0),
            safeSize,
            Sort.by(Sort.Direction.DESC, "createdAt")
        );
        return ResponseEntity.ok(
            subscriptionQueryService.getHistory(
                currentTenantProvider.getRequiredRestaurantId(),
                pageable
            )
        );
    }

    @GetMapping("/api/v1/me/entitlements")
    ResponseEntity<CurrentEntitlementResponse> entitlements() {
        return ResponseEntity.ok(
            subscriptionQueryService.getCurrent(currentTenantProvider.getRequiredRestaurantId())
        );
    }
}
