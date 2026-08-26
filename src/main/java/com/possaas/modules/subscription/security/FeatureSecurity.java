package com.possaas.modules.subscription.security;

import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("featureSecurity")
@RequiredArgsConstructor
public class FeatureSecurity {
    private final CurrentUserProvider currentUserProvider;
    private final FeatureAccessChecker featureAccessChecker;

    public boolean hasCurrentTenantFeature(String featureCode) {
        CurrentUser currentUser = currentUserProvider.getRequired();
        if (currentUser.restaurantId() == null) {
            return false;
        }
        return featureAccessChecker.hasFeature(currentUser.restaurantId(), featureCode);
    }
}
