package com.possaas.modules.order.service;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.*;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Guard every summary read before loading order/snapshot data, using one entitlement read. */
@Component @RequiredArgsConstructor
public class OrderCostSnapshotAccess {
    private final CurrentTenantProvider tenants;
    private final CurrentUserProvider users;
    private final CurrentEntitlementQuery entitlements;
    public CurrentUser requireRead() {
        var tenant=tenants.getRequiredRestaurantId();var actor=users.getRequired();
        if(!tenant.equals(actor.restaurantId())||!actor.hasPermission("ORDER_VIEW")||!actor.hasPermission("REPORT_DETAIL_VIEW"))
            throw new AccessDeniedException("Order and detailed report view permissions are required");
        var entitlement=entitlements.getForRestaurant(tenant);
        if(!entitlement.hasFeature("ORDER_MANAGEMENT")||!entitlement.hasFeature("DETAIL_REPORT"))
            throw new BusinessException(HttpStatus.FORBIDDEN,"FEATURE_NOT_ENTITLED","Order and detailed report features are required");
        return actor;
    }
}
