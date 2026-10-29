package com.possaas.modules.inventory.application.port;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Historical, tenant-filtered usage facts supplied by ORDER without live catalogue or feature checks. */
public interface RecipeUsageCostQuery {
    /** Includes legacy usages with null monetary metadata; absence means the historical fact is missing. */
    Map<UUID, UsageCost> findCosts(UUID tenant, Collection<UUID> recipeUsageIds);

    record UsageCost(UUID orderId, UUID orderItemId, UUID recipeSnapshotId, UUID recipeUsageId,
            UUID ingredientId, String unit, BigDecimal quantity, Short costSnapshotVersion,
            Boolean costConfigured, BigDecimal unitCost) {}
}
