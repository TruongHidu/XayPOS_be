package com.possaas.modules.menu.service;

import com.possaas.modules.menu.dto.IngredientResponse;
import com.possaas.modules.menu.entity.Item;
import org.springframework.stereotype.Component;

@Component
public class IngredientResponseMapper {
    public IngredientResponse map(Item item) {
        return new IngredientResponse(item.getId(), item.getSku(), item.getName(), item.getBaseUnit(),
                item.getDescription(), item.getCostPrice(), item.isTrackInventory(), item.isActive(),
                item.getVersion(), item.getCreatedAt(), item.getUpdatedAt(), item.isCostConfigured());
    }
}
