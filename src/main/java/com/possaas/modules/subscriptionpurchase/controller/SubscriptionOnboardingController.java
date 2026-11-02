package com.possaas.modules.subscriptionpurchase.controller;

import com.possaas.modules.subscriptionpurchase.dto.PurchaseResponses;
import com.possaas.modules.subscriptionpurchase.service.PurchaseQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
public class SubscriptionOnboardingController {
    private final PurchaseQueryService queries;

    @GetMapping("/api/v1/subscriptions/onboarding")
    @PreAuthorize("hasRole('OWNER') and hasAuthority('SUBSCRIPTION_PURCHASE_VIEW')")
    public PurchaseResponses.Onboarding onboarding() { return queries.onboarding(); }
}
