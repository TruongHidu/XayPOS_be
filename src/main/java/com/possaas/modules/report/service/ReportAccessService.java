package com.possaas.modules.report.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReportAccessService {
    public static final String DAILY_REVENUE = "DAILY_REVENUE";
    public static final String DETAIL_REPORT = "DETAIL_REPORT";

    private final CurrentTenantProvider tenants;
    private final RestaurantRepository restaurants;
    private final FeatureAccessChecker features;

    public Restaurant requireDailyReport() {
        return require(DAILY_REVENUE);
    }

    public Restaurant requireDetailedReport() {
        return require(DETAIL_REPORT);
    }

    private Restaurant require(String featureCode) {
        UUID restaurantId = tenants.getRequiredRestaurantId();
        features.requireFeature(restaurantId, featureCode);
        return restaurants.findByIdAndDeletedAtIsNull(restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "RESTAURANT_NOT_FOUND",
                "Restaurant not found"
            ));
    }
}
