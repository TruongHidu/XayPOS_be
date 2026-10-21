package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.menu.entity.Item;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class IngredientLifecyclePolicy {
    public void requireVersion(Item ingredient, long expectedVersion) {
        if (ingredient.getVersion() != expectedVersion)
            throw new ConflictException("CONCURRENT_INGREDIENT_UPDATE",
                    "Ingredient changed; reload before retrying");
    }

    public void changeActive(Item ingredient, boolean active) {
        if (ingredient.getDeletedAt() != null)
            throw new ConflictException("INVALID_INGREDIENT_TRANSITION",
                    "Deleted ingredients cannot be changed");
        ingredient.setActive(active);
    }

    public void softDelete(Item ingredient, Instant now) {
        if (ingredient.getDeletedAt() == null)
            ingredient.setDeletedAt(now);
    }
}
