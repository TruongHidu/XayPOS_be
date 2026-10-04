package com.possaas.modules.menu.service;

import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.service.strategy.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MenuItemFactory {
    private final ItemCreationStrategyRegistry registry;

    public Item create(ItemType type, ItemCreationContext context) {
        return registry.get(type).create(context);
    }
}
