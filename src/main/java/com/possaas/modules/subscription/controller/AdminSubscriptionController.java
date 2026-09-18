package com.possaas.modules.subscription.controller;

import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.subscription.dto.ChangePackageRequest;
import com.possaas.modules.subscription.dto.CreateSubscriptionRequest;
import com.possaas.modules.subscription.dto.SubscriptionResponse;
import com.possaas.modules.subscription.service.SubscriptionCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/restaurants/{restaurantId}/subscriptions")
@RequiredArgsConstructor
public class AdminSubscriptionController {
    private final SubscriptionCommandService subscriptionCommandService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_MANAGE')"
    )
    ResponseEntity<SubscriptionResponse> create(
        @PathVariable UUID restaurantId,
        @Valid @RequestBody CreateSubscriptionRequest request,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.status(HttpStatus.CREATED).body(
            subscriptionCommandService.create(
                restaurantId,
                request,
                actor.userId(),
                httpRequest.getRemoteAddr()
            )
        );
    }

    @PostMapping("/{subscriptionId}/activate")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_MANAGE')"
    )
    ResponseEntity<SubscriptionResponse> activate(
        @PathVariable UUID restaurantId,
        @PathVariable UUID subscriptionId,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.ok(
            subscriptionCommandService.activate(
                restaurantId,
                subscriptionId,
                actor.userId(),
                httpRequest.getRemoteAddr()
            )
        );
    }

    @PostMapping("/{subscriptionId}/change-package")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_MANAGE')"
    )
    ResponseEntity<SubscriptionResponse> changePackage(
        @PathVariable UUID restaurantId,
        @PathVariable UUID subscriptionId,
        @Valid @RequestBody ChangePackageRequest request,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.ok(
            subscriptionCommandService.changePackage(
                restaurantId,
                subscriptionId,
                request,
                actor.userId(),
                httpRequest.getRemoteAddr()
            )
        );
    }

    @PostMapping("/{subscriptionId}/cancel")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_MANAGE')"
    )
    ResponseEntity<SubscriptionResponse> cancel(
        @PathVariable UUID restaurantId,
        @PathVariable UUID subscriptionId,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.ok(
            subscriptionCommandService.cancel(
                restaurantId,
                subscriptionId,
                actor.userId(),
                httpRequest.getRemoteAddr()
            )
        );
    }
}
