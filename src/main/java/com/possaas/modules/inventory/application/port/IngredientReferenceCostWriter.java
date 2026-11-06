package com.possaas.modules.inventory.application.port;

import com.possaas.common.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Ingredient prices are owned by MENU; callers must already hold the tenant mutation lock. */
public interface IngredientReferenceCostWriter {
    Map<UUID, BigDecimal> lockReferenceCosts(UUID restaurantId, Collection<UUID> ingredientIds);

    Map<UUID, ReferenceCost> lockReferenceCostStates(UUID restaurantId, Collection<UUID> ingredientIds);

    record ReferenceCost(BigDecimal price, boolean configured) {}

    void updateReferenceCosts(CurrentUser actor, UUID sourceDocumentId, Map<UUID, BigDecimal> costs,
            Instant timestamp, String ipAddress);
}
