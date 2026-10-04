package com.possaas.modules.menu.service.strategy;

import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.service.MenuItemValidationPolicy;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MenuItemCreationStrategy implements ItemCreationStrategy {
    private final MenuItemValidationPolicy validation;

    @Override
    public ItemType supportedType() {
        return ItemType.MENU_ITEM;
    }

    @Override
    public Item create(ItemCreationContext c) {
        java.util.Objects.requireNonNull(c.restaurantId());
        java.util.Objects.requireNonNull(c.now());
        Item item = new Item();
        item.initialize(c.restaurantId(), c.now());
        item.setItemType(ItemType.MENU_ITEM);
        item.setTrackInventory(false);
        item.setCostPrice(BigDecimal.ZERO);
        item.setMetadata(new LinkedHashMap<>());
        item.setAvailabilityStatus(AvailabilityStatus.AVAILABLE);
        item.setGroupId(c.groupId());
        item.setName(validation.required(c.name(), 150));
        item.setBaseUnit(validation.required(c.baseUnit(), 30));
        item.setSku(validation.sku(c.sku()));
        item.setDescription(validation.optional(c.description()));
        item.setImageUrl(validation.image(c.imageUrl()));
        item.setSalePrice(validation.price(c.salePrice()));
        item.setActive(c.active());
        return item;
    }
}
