package com.possaas.modules.menu.service.cost;

import com.possaas.modules.menu.repository.ItemRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IngredientCostPriceProvider implements IngredientCostProvider {
    private final ItemRepository items;

    @Override
    public String costSource() {
        return "INGREDIENT_COST_PRICE";
    }

    @Override
    public Map<UUID, IngredientCost> findCosts(UUID restaurantId, Collection<UUID> ingredientIds) {
        if (ingredientIds.isEmpty()) return Map.of();
        // Historical recipes can reference inactive/soft-deleted ingredients. Tenant and type still apply.
        return items.findIngredientReferenceCosts(restaurantId, ingredientIds).stream()
                .collect(Collectors.toUnmodifiableMap(ItemRepository.IngredientReferenceCost::getIngredientId,
                        row -> new IngredientCost(row.getIngredientId(), row.getBaseUnit(), row.getUnitCost())));
    }
}
