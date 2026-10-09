package com.possaas.modules.menu.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PublicMenuAccessPolicy {
    private final FeatureAccessChecker features;

    public void requireAccess(UUID restaurantId) { requireAccess(restaurantId, "PUBLIC_MENU_UNAVAILABLE"); }

    // The table entry point retains its established error contract.
    void requireAccess(UUID restaurantId, String unavailableCode) {
        try {
            features.requireFeature(restaurantId, "QR_MENU_VIEW");
        } catch (BusinessException failure) {
            if (!"SUBSCRIPTION_NOT_ACTIVE".equals(failure.getCode())
                    && !"FEATURE_NOT_ENTITLED".equals(failure.getCode())) throw failure;
            throw new BusinessException(HttpStatus.FORBIDDEN, unavailableCode, "Menu hiện chưa khả dụng.");
        }
    }
}
