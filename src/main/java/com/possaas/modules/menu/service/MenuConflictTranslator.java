package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import org.hibernate.exception.ConstraintViolationException;

public final class MenuConflictTranslator {
    private MenuConflictTranslator() {
    }

    public static ConflictException translate(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException c) {
                if ("ux_menu_item_sku".equals(c.getConstraintName()))
                    return new ConflictException("SKU_EXISTS", "SKU is already reserved in this restaurant");
                if ("ux_menu_group_name".equals(c.getConstraintName()))
                    return new ConflictException("MENU_GROUP_NAME_EXISTS", "Group name already exists");
            }
        }
        return new ConflictException("CONCURRENT_MENU_UPDATE",
                "Menu changed concurrently or violates a persistence constraint");
    }
}
