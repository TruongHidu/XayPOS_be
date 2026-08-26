package com.possaas.modules.subscription.mapper;

import com.possaas.modules.subscription.domain.CurrentEntitlement;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.dto.CurrentEntitlementResponse;
import com.possaas.modules.subscription.dto.FeatureEntitlementResponse;
import com.possaas.modules.subscription.dto.SubscriptionResponse;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionMapper {
    public SubscriptionResponse toResponse(RestaurantSubscription subscription, String packageCode) {
        SubscriptionFeatureSnapshot snapshot = SubscriptionFeatureSnapshot.fromMap(subscription.getFeatureSnapshot());
        return new SubscriptionResponse(
            subscription.getId(),
            subscription.getRestaurantId(),
            subscription.getPackageId(),
            packageCode,
            subscription.getStatus(),
            subscription.getStartAt(),
            subscription.getEndAt(),
            subscription.isAutoRenew(),
            subscription.getPriceAmount(),
            subscription.getCurrencyCode(),
            subscription.getActivatedAt(),
            subscription.getCancelledAt(),
            toFeatureResponses(snapshot.features())
        );
    }

    public CurrentEntitlementResponse toResponse(CurrentEntitlement entitlement) {
        return new CurrentEntitlementResponse(
            entitlement.subscriptionId(),
            entitlement.packageCode(),
            entitlement.status(),
            entitlement.startAt(),
            entitlement.endAt(),
            toFeatureResponses(entitlement.features())
        );
    }

    private List<FeatureEntitlementResponse> toFeatureResponses(
        List<SubscriptionFeatureSnapshot.FeatureGrant> features
    ) {
        return features.stream()
            .map(feature -> new FeatureEntitlementResponse(feature.code(), feature.limits()))
            .toList();
    }
}
