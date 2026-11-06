package com.possaas.modules.report.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReferenceMarginReportAccess {
    private final CurrentTenantProvider tenants;
    private final CurrentUserProvider users;
    private final CurrentEntitlementQuery entitlements;
    private final RestaurantRepository restaurants;

    public Restaurant requireRead() {
        var tenant = tenants.getRequiredRestaurantId();
        var actor = users.getRequired();
        if (!tenant.equals(actor.restaurantId()) || !actor.hasPermission("ORDER_VIEW")
                || !actor.hasPermission("REPORT_DETAIL_VIEW")) {
            throw new AccessDeniedException("Order and detailed report view permissions are required");
        }
        var entitlement = entitlements.getForRestaurant(tenant);
        if (!entitlement.hasFeature("ORDER_MANAGEMENT") || !entitlement.hasFeature("DETAIL_REPORT")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FEATURE_NOT_ENTITLED",
                "Order and detailed report features are required");
        }
        return restaurants.findByIdAndDeletedAtIsNull(tenant)
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
    }
}
