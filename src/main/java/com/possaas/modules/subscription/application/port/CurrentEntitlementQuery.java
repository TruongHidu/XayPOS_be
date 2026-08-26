package com.possaas.modules.subscription.application.port;

import com.possaas.modules.subscription.domain.CurrentEntitlement;
import java.util.UUID;

public interface CurrentEntitlementQuery {
    CurrentEntitlement getForRestaurant(UUID restaurantId);
}
