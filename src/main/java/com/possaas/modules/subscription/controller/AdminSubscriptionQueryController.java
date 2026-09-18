package com.possaas.modules.subscription.controller;

import com.possaas.modules.subscription.dto.AdminSubscriptionDetailResponse;
import com.possaas.modules.subscription.dto.AdminSubscriptionSummaryResponse;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.AdminSubscriptionCriteria;
import com.possaas.modules.subscription.service.AdminSubscriptionQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminSubscriptionQueryController {
    private final AdminSubscriptionQueryService queryService;

    @GetMapping("/subscriptions")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_VIEW')"
    )
    ResponseEntity<PageResponse<AdminSubscriptionSummaryResponse>> search(
        @RequestParam(required = false) @Size(max = 100) String q,
        @RequestParam(required = false) UUID restaurantId,
        @RequestParam(required = false) String packageCode,
        @RequestParam(required = false) SubscriptionStatus status,
        @RequestParam(required = false) Boolean effective,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "desc") String direction
    ) {
        return ResponseEntity.ok(queryService.search(
            new AdminSubscriptionCriteria(q, restaurantId, packageCode, status, effective),
            page,
            size,
            sortBy,
            direction
        ));
    }

    @GetMapping("/restaurants/{restaurantId}/subscriptions")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_VIEW')"
    )
    ResponseEntity<PageResponse<AdminSubscriptionSummaryResponse>> byRestaurant(
        @PathVariable UUID restaurantId,
        @RequestParam(required = false) SubscriptionStatus status,
        @RequestParam(required = false) String packageCode,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(queryService.findByRestaurant(
            restaurantId,
            packageCode,
            status,
            page,
            size
        ));
    }

    @GetMapping("/restaurants/{restaurantId}/subscriptions/{subscriptionId}")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_VIEW')"
    )
    ResponseEntity<AdminSubscriptionDetailResponse> detail(
        @PathVariable UUID restaurantId,
        @PathVariable UUID subscriptionId
    ) {
        return ResponseEntity.ok(queryService.findDetail(restaurantId, subscriptionId));
    }
}
