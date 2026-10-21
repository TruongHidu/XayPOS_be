package com.possaas.modules.menu.service.strategy;

import com.possaas.modules.menu.entity.AvailabilityStatus;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.service.MenuItemValidationPolicy;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IngredientCreationStrategy implements ItemCreationStrategy {
    private final MenuItemValidationPolicy validation;

    @Override
    public ItemType supportedType() {
        return ItemType.INGREDIENT;
    }

    @Override
    public Item create(ItemCreationContext context) {
        Objects.requireNonNull(context.restaurantId());
        Objects.requireNonNull(context.now());
        Item ingredient = new Item();
        ingredient.initialize(context.restaurantId(), context.now());
        ingredient.setItemType(ItemType.INGREDIENT);
        ingredient.setGroupId(null);
        ingredient.setSku(validation.sku(context.sku()));
        ingredient.setName(validation.required(context.name(), 150));
        ingredient.setBaseUnit(validation.required(context.baseUnit(), 30));
        ingredient.setDescription(validation.optional(context.description()));
        ingredient.setImageUrl(null);
        ingredient.setSalePrice(BigDecimal.ZERO.setScale(2));
        ingredient.setCostPrice(validation.price(context.costPrice()));
        ingredient.setTrackInventory(context.trackInventory());
        ingredient.setAvailabilityStatus(AvailabilityStatus.AVAILABLE);
        ingredient.setActive(context.active());
        ingredient.setMetadata(new LinkedHashMap<>());
        return ingredient;
    }
}
