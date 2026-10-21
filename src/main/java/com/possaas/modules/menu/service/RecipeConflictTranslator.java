package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import org.hibernate.exception.ConstraintViolationException;

public final class RecipeConflictTranslator {
    private RecipeConflictTranslator() {
    }

    public static ConflictException translate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof ConstraintViolationException constraint
                    && ("ux_recipe_active".equals(constraint.getConstraintName())
                    || "uq_recipe_business_version".equals(constraint.getConstraintName())
                    || "uq_recipe_item_ingredient".equals(constraint.getConstraintName())))
                return new ConflictException("CONCURRENT_RECIPE_UPDATE",
                        "Recipe changed concurrently; reload before retrying");
        return new ConflictException("CONCURRENT_RECIPE_UPDATE",
                "Recipe changed concurrently or violates a persistence constraint");
    }
}
