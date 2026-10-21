package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import org.hibernate.exception.ConstraintViolationException;

public final class InventoryConflictTranslator {
    private InventoryConflictTranslator() {
    }

    public static ConflictException translate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) {
                String name = constraint.getConstraintName();
                if ("uq_warehouse_tenant_code".equals(name))
                    return new ConflictException("WAREHOUSE_CODE_EXISTS",
                            "Warehouse code already exists");
                if ("uq_inventory_balance_warehouse_item".equals(name))
                    return inventoryConflict();
                if ("ux_warehouse_default".equals(name))
                    return warehouseConflict();
                if (name != null && name.startsWith("ck_inventory_balance_reserved_"))
                    return new ConflictException("INVENTORY_RESERVATION_INVARIANT_VIOLATION",
                            "Inventory reservations are inconsistent");
                if (name != null && (name.startsWith("uq_order_inventory_")
                        || name.startsWith("uq_inventory_reservation_")
                        || name.startsWith("ck_order_inventory_")
                        || name.startsWith("ck_inventory_reservation_")
                        || name.startsWith("fk_order_inventory_")
                        || name.startsWith("fk_inventory_reservation_")
                        || name.startsWith("fk_inventory_recipe_use_")
                        || "uq_recipe_use_reservation".equals(name)
                        || "ck_inventory_transaction_source".equals(name)))
                    return new ConflictException("INVENTORY_ALLOCATION_CONFLICT",
                            "Order inventory allocation is inconsistent");
            }
        }
        String type = failure.getClass().getSimpleName().toLowerCase();
        return type.contains("warehouse") ? warehouseConflict() : inventoryConflict();
    }

    public static ConflictException warehouseConflict() {
        return new ConflictException("CONCURRENT_WAREHOUSE_UPDATE",
                "Warehouse changed concurrently or violates a persistence constraint");
    }

    public static ConflictException inventoryConflict() {
        return new ConflictException("CONCURRENT_INVENTORY_UPDATE",
                "Inventory balance changed concurrently or violates a persistence constraint");
    }
}
