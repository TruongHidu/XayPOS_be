package com.possaas.modules.order.service;
import com.possaas.modules.inventory.application.port.RecipeUsageCostQuery;
import com.possaas.modules.order.repository.OrderItemRecipeUsageRepository;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Only frozen ORDER facts cross this boundary; never query MENU or current entitlement here. */
@Component @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
public class InventoryRecipeUsageCostAdapter implements RecipeUsageCostQuery {
    private final OrderItemRecipeUsageRepository usages;
    @Override public Map<UUID,UsageCost> findCosts(UUID tenant,Collection<UUID> ids) {
        if(ids.isEmpty()) return Map.of();
        return usages.findFrozenCosts(tenant,ids).stream().collect(Collectors.toUnmodifiableMap(
            row->row.getRecipeUsageId(),row->new UsageCost(row.getOrderId(),row.getOrderItemId(),row.getRecipeSnapshotId(),
                row.getRecipeUsageId(),row.getIngredientId(),row.getUnit(),row.getQuantity(),row.getCostSnapshotVersion(),
                row.getCostConfigured(),row.getUnitCost())));
    }
}
