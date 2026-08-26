package com.possaas.modules.subscription.domain;

import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CurrentEntitlement(
    UUID subscriptionId,
    UUID restaurantId,
    String packageCode,
    SubscriptionStatus status,
    Instant startAt,
    Instant endAt,
    List<SubscriptionFeatureSnapshot.FeatureGrant> features
) {
    public CurrentEntitlement {
        features = features == null ? List.of() : List.copyOf(features);
    }

    public boolean hasFeature(String featureCode) {
        return features.stream().anyMatch(feature -> feature.code().equals(featureCode));
    }
}
