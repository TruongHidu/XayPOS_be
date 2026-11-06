package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import org.hibernate.exception.ConstraintViolationException;

public final class IngredientConflictTranslator {
    private IngredientConflictTranslator() {
    }

    public static ConflictException translate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof ConstraintViolationException constraint
                    && "ux_menu_item_sku".equals(constraint.getConstraintName()))
                return new ConflictException("SKU_EXISTS", "SKU is already reserved in this restaurant");
        return new ConflictException("CONCURRENT_INGREDIENT_UPDATE",
                "Ingredient changed concurrently or violates a persistence constraint");
    }
}
