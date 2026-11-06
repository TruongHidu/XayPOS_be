package com.possaas.modules.payment.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class PaymentAccessService {
    private final CurrentTenantProvider tenants;
    private final CurrentUserProvider users;
    private final RestaurantRepository restaurants;
    private final FeatureAccessChecker features;

    public CurrentUser read() {
        UUID tenant = tenants.getRequiredRestaurantId();
        features.requireFeature(tenant, "PAYMENT_MANAGEMENT");
        return users.getRequired();
    }

    /** Same restaurant lock as ORDER/TABLE mutations, without ORDER_MANAGEMENT entitlement. */
    public CurrentUser lock() {
        UUID tenant = tenants.getRequiredRestaurantId();
        restaurants.findByIdForUpdate(tenant)
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        features.requireFeature(tenant, "PAYMENT_MANAGEMENT");
        return users.getRequired();
    }
}
