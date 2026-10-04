package com.possaas.modules.menu.service.strategy;

import com.possaas.modules.menu.entity.ItemType;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ItemCreationStrategyRegistry {
    private final Map<ItemType, ItemCreationStrategy> strategies;

    public ItemCreationStrategyRegistry(List<ItemCreationStrategy> implementations) {
        Map<ItemType, ItemCreationStrategy> map = new EnumMap<>(ItemType.class);
        for (var s : implementations)
            if (map.put(s.supportedType(), s) != null)
                throw new IllegalStateException("Duplicate item creation strategy: " + s.supportedType());
        if (!map.containsKey(ItemType.MENU_ITEM))
            throw new IllegalStateException("MENU_ITEM creation strategy is required");
        strategies = Map.copyOf(map);
    }

    public ItemCreationStrategy get(ItemType type) {
        var strategy = strategies.get(type);
        if (strategy == null)
            throw new IllegalArgumentException("Unsupported item creation type: " + type);
        return strategy;
    }
}
