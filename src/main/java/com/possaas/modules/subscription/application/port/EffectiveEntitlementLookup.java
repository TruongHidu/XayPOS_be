package com.possaas.modules.subscription.application.port;

import com.possaas.modules.subscription.domain.CurrentEntitlement;
import java.util.Optional;
import java.util.UUID;

/** Non-throwing variant for read models that must render restaurants without an effective subscription. */
public interface EffectiveEntitlementLookup {
    Optional<CurrentEntitlement> findForRestaurant(UUID restaurantId);
}
