package com.possaas.modules.subscription.mapper;

import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.dto.AdminPackageReferenceResponse;
import com.possaas.modules.subscription.dto.AdminRestaurantReferenceResponse;
import com.possaas.modules.subscription.dto.AdminSubscriptionDetailResponse;
import com.possaas.modules.subscription.dto.AdminSubscriptionSummaryResponse;
import com.possaas.modules.subscription.dto.FeatureEntitlementResponse;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class AdminSubscriptionMapper {
    public AdminSubscriptionSummaryResponse toSummary(
        RestaurantSubscription subscription,
        Restaurant restaurant,
        PackagePlan packagePlan,
        Instant now
    ) {
        return new AdminSubscriptionSummaryResponse(
            subscription.getId(),
            restaurantReference(restaurant),
            packageReference(packagePlan),
            subscription.getStatus(),
            isEffective(subscription, now),
            subscription.getStartAt(),
            subscription.getEndAt(),
            subscription.isAutoRenew(),
            subscription.getPriceAmount(),
            subscription.getCurrencyCode(),
            subscription.getActivatedAt(),
            subscription.getCancelledAt(),
            subscription.getCreatedAt(),
            subscription.getUpdatedAt()
        );
    }

    public AdminSubscriptionDetailResponse toDetail(
        RestaurantSubscription subscription,
        Restaurant restaurant,
        PackagePlan packagePlan,
        Instant now
    ) {
        SubscriptionFeatureSnapshot snapshot = SubscriptionFeatureSnapshot.fromMap(
            subscription.getFeatureSnapshot()
        );
        return new AdminSubscriptionDetailResponse(
            subscription.getId(),
            restaurantReference(restaurant),
            packageReference(packagePlan),
            subscription.getStatus(),
            isEffective(subscription, now),
            subscription.getStartAt(),
            subscription.getEndAt(),
            subscription.isAutoRenew(),
            subscription.getPriceAmount(),
            subscription.getCurrencyCode(),
            subscription.getActivatedAt(),
            subscription.getCancelledAt(),
            subscription.getCreatedAt(),
            subscription.getUpdatedAt(),
            snapshot.features().stream()
                .map(feature -> new FeatureEntitlementResponse(feature.code(), feature.limits()))
                .toList()
        );
    }

    private static AdminRestaurantReferenceResponse restaurantReference(Restaurant restaurant) {
        return new AdminRestaurantReferenceResponse(
            restaurant.getId(),
            restaurant.getCode(),
            restaurant.getName(),
            restaurant.getStatus()
        );
    }

    private static AdminPackageReferenceResponse packageReference(PackagePlan packagePlan) {
        return new AdminPackageReferenceResponse(
            packagePlan.getId(),
            packagePlan.getCode(),
            packagePlan.getName()
        );
    }

    private static boolean isEffective(RestaurantSubscription subscription, Instant now) {
        return subscription.getStatus() == SubscriptionStatus.ACTIVE
            && !subscription.getStartAt().isAfter(now)
            && subscription.getEndAt().isAfter(now);
    }
}
