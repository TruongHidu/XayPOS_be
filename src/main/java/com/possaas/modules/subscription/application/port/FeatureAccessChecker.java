package com.possaas.modules.subscription.application.port;

import java.util.UUID;

public interface FeatureAccessChecker {
    boolean hasFeature(UUID restaurantId, String featureCode);

    void requireFeature(UUID restaurantId, String featureCode);
}
