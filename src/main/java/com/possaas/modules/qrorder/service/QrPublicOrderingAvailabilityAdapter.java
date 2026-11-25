package com.possaas.modules.qrorder.service;

import com.possaas.modules.menu.application.port.PublicOrderingAvailabilityQuery;
import com.possaas.modules.qrorder.entity.QrEntryType;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The same two gates QrScopePolicy enforces (owner switch and package entitlement), asked without
 * throwing: a denied business check must not mark the caller's read-only transaction rollback-only.
 */
@Component
@RequiredArgsConstructor
public class QrPublicOrderingAvailabilityAdapter implements PublicOrderingAvailabilityQuery {
    private final RestaurantRepository restaurants;
    private final FeatureAccessChecker features;

    @Override public boolean tableOrderingEnabled(UUID restaurantId) {
        return available(restaurantId, QrEntryType.TABLE);
    }

    @Override public boolean takeawayOrderingEnabled(UUID restaurantId) {
        return available(restaurantId, QrEntryType.RESTAURANT);
    }

    private boolean available(UUID restaurantId, QrEntryType type) {
        boolean switchedOn = restaurants.findByIdAndDeletedAtIsNull(restaurantId)
            .map(r -> r.qrOrdering().allows(type.orderingEntry())).orElse(false);
        return switchedOn && type.requiredFeatures().stream().allMatch(f -> features.hasFeature(restaurantId, f));
    }
}
