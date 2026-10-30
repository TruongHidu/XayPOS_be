package com.possaas.modules.cashbook.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.*;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class CashbookAccessService {
    private final CurrentTenantProvider tenants;
    private final CurrentUserProvider users;
    private final CurrentEntitlementQuery entitlements;
    private final RestaurantRepository restaurants;
    public Access read(boolean inventory) { return require("CASHBOOK_VIEW",inventory,false); }
    public Access create(boolean linked) { return require("CASHBOOK_CREATE",linked,true); }
    public Access voidExpense() { return require("CASHBOOK_VOID",false,true); }
    private Access require(String permission,boolean inventory,boolean mutation) {
        var tenant=tenants.getRequiredRestaurantId();var actor=users.getRequired();
        if(!tenant.equals(actor.restaurantId())||!actor.hasPermission(permission)
                ||(inventory&&!actor.hasPermission("INVENTORY_VIEW")))
            throw new AccessDeniedException("Required cashbook permission is missing");
        var entitlement=entitlements.getForRestaurant(tenant);
        if(!entitlement.hasFeature("DETAIL_REPORT")||(inventory&&!entitlement.hasFeature("INVENTORY_MANAGEMENT")))
            throw new BusinessException(HttpStatus.FORBIDDEN,"FEATURE_NOT_ENTITLED","The current package does not include this cashbook action");
        var restaurant=(mutation?restaurants.findByIdForUpdate(tenant):restaurants.findByIdAndDeletedAtIsNull(tenant))
            .orElseThrow(()->new ResourceNotFoundException("RESTAURANT_NOT_FOUND","Restaurant not found"));
        return new Access(actor,restaurant);
    }
    public record Access(CurrentUser actor,Restaurant restaurant) {}
}
