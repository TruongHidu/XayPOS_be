package com.possaas.modules.restaurant.service;

import com.possaas.modules.restaurant.application.port.RestaurantUsageQuery;
import com.possaas.modules.restaurant.dto.AdminRestaurantUsageResponse;
import com.possaas.modules.subscription.application.port.EffectiveEntitlementLookup;
import com.possaas.modules.subscription.domain.CurrentEntitlement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Usage of a restaurant over the last 30 days, compared with its effective package. */
@Service
@RequiredArgsConstructor
public class AdminRestaurantUsageService {
    static final Duration WINDOW = Duration.ofDays(30);

    private final AdminRestaurantLookup restaurants;
    private final RestaurantUsageQuery usage;
    private final EffectiveEntitlementLookup entitlements;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AdminRestaurantUsageResponse usage(UUID restaurantId) {
        restaurants.requireExisting(restaurantId);
        var counters = usage.count(restaurantId, clock.instant().minus(WINDOW));
        CurrentEntitlement entitlement = entitlements.findForRestaurant(restaurantId).orElse(null);
        return new AdminRestaurantUsageResponse(
            entitlement == null ? null : entitlement.packageCode(),
            entitlement == null ? null : entitlement.endAt(),
            entitlement == null ? null : entitlement.maxStaff(),
            counters.activeStaff(), counters.totalUsers(), counters.activeTables(), counters.activeMenuItems(),
            counters.orders(), counters.completedSales(), counters.lastOrderAt(), counters.lastLoginAt(),
            entitlement == null ? List.of() : entitlement.features().stream().map(f -> f.code()).sorted().toList());
    }
}
