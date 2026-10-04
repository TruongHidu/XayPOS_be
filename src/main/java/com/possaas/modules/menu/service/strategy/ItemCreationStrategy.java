package com.possaas.modules.menu.service.strategy;

import com.possaas.modules.menu.entity.*;

public interface ItemCreationStrategy {
    ItemType supportedType();

    Item create(ItemCreationContext context);
}
