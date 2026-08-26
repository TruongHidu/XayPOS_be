package com.possaas.modules.subscription.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import com.possaas.modules.subscription.domain.CurrentEntitlement;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EntitlementService implements FeatureAccessChecker, CurrentEntitlementQuery {
    private final RestaurantSubscriptionRepository subscriptionRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public CurrentEntitlement getForRestaurant(UUID restaurantId) {
        RestaurantSubscription subscription = subscriptionRepository
            .findEffectiveSubscription(restaurantId, clock.instant())
            .orElseThrow(EntitlementService::subscriptionNotActive);
        SubscriptionFeatureSnapshot snapshot = SubscriptionFeatureSnapshot.fromMap(
            subscription.getFeatureSnapshot()
        );
        return new CurrentEntitlement(
            subscription.getId(),
            restaurantId,
            snapshot.packageCode(),
            subscription.getStatus(),
            subscription.getStartAt(),
            subscription.getEndAt(),
            snapshot.features()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasFeature(UUID restaurantId, String featureCode) {
        return subscriptionRepository.findEffectiveSubscription(restaurantId, clock.instant())
            .map(RestaurantSubscription::getFeatureSnapshot)
            .map(SubscriptionFeatureSnapshot::fromMap)
            .map(snapshot -> snapshot.contains(normalizeFeatureCode(featureCode)))
            .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireFeature(UUID restaurantId, String featureCode) {
        CurrentEntitlement entitlement = getForRestaurant(restaurantId);
        if (!entitlement.hasFeature(normalizeFeatureCode(featureCode))) {
            throw new BusinessException(
                HttpStatus.FORBIDDEN,
                "FEATURE_NOT_ENTITLED",
                "The current package does not include this feature"
            );
        }
    }

    private static String normalizeFeatureCode(String featureCode) {
        return featureCode == null ? "" : featureCode.trim().toUpperCase(Locale.ROOT);
    }

    private static BusinessException subscriptionNotActive() {
        return new BusinessException(
            HttpStatus.FORBIDDEN,
            "SUBSCRIPTION_NOT_ACTIVE",
            "No active subscription is effective for this restaurant"
        );
    }
}
