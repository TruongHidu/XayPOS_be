package com.possaas.modules.menu.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.application.port.IngredientReferenceCostWriter;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.repository.ItemRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryIngredientCostAdapter implements IngredientReferenceCostWriter {
    private final ItemRepository items;
    private final IngredientAudit audit;

    @Override
    public Map<UUID, BigDecimal> lockReferenceCosts(UUID restaurantId, Collection<UUID> ingredientIds) {
        return requireIngredients(items.findIngredientsForUpdate(restaurantId, ingredientIds), ingredientIds)
                .stream().collect(Collectors.toMap(Item::getId, Item::getCostPrice));
    }

    @Override
    public Map<UUID, ReferenceCost> lockReferenceCostStates(UUID restaurantId, Collection<UUID> ingredientIds) {
        return requireIngredients(items.findIngredientsForUpdate(restaurantId, ingredientIds), ingredientIds)
                .stream().collect(Collectors.toMap(Item::getId, item -> new ReferenceCost(item.getCostPrice(), item.isCostConfigured())));
    }

    @Override
    public void updateReferenceCosts(CurrentUser actor, UUID sourceDocumentId, Map<UUID, BigDecimal> costs,
            Instant timestamp, String ipAddress) {
        Map<UUID, Item> ingredients = requireIngredients(
                items.findAllByRestaurantIdAndIdIn(actor.restaurantId(), costs.keySet()), costs.keySet())
                .stream().collect(Collectors.toMap(Item::getId, Function.identity()));
        for (var entry : costs.entrySet()) {
            Item ingredient = ingredients.get(entry.getKey());
            if (ingredient.getCostPrice().compareTo(entry.getValue()) == 0 && ingredient.isCostConfigured()) continue;
            var before = audit.snapshot(ingredient);
            before.put("sourceDocumentId", sourceDocumentId);
            ingredient.setCostPrice(entry.getValue());
            ingredient.setCostConfigured(true);
            ingredient.setUpdatedAt(timestamp);
            var after = audit.snapshot(ingredient);
            after.put("sourceDocumentId", sourceDocumentId);
            after.put("costMethod", "MOVING_WEIGHTED_AVERAGE");
            audit.record(actor, "INGREDIENT_COST_RECALCULATED", ingredient, before, after, ipAddress);
        }
        // Managed entities are flushed by the enclosing stock confirmation transaction.
    }

    private static List<Item> requireIngredients(List<Item> values, Collection<UUID> expected) {
        List<Item> tracked = values.stream().filter(item -> item.getItemType() == ItemType.INGREDIENT
                && item.getDeletedAt() == null && item.isTrackInventory()).toList();
        if (tracked.size() != expected.stream().distinct().count())
            throw new ResourceNotFoundException("TRACKED_INGREDIENT_NOT_FOUND", "Tracked ingredient not found");
        return tracked;
    }
}
