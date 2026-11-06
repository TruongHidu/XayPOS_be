package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;

/** Maps only the inventory constraints reachable through ORDER commands; no inventory service dependency. */
public final class OrderInventoryPersistenceConflict {
    private OrderInventoryPersistenceConflict() {}

    public static ConflictException translate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) {
                var mapped = named(constraint.getConstraintName());
                if (mapped != null) return mapped;
            }
            // Deferred trigger failures can surface at commit through a transaction wrapper.
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("23")) {
                var mapped = named(sql.getMessage());
                if (mapped != null) return mapped;
            }
        }
        return null;
    }

    private static ConflictException named(String name) {
        if (name == null) return null;
        if (name.contains("ck_inventory_balance_reserved_non_negative")
                || name.contains("ck_inventory_balance_reserved_physical"))
            return new ConflictException("INVENTORY_RESERVATION_INVARIANT_VIOLATION",
                    "Inventory reservation balance is inconsistent");
        if (name.contains("uq_order_inventory_") || name.contains("fk_order_inventory_")
                || name.contains("ck_order_inventory_") || name.contains("uq_inventory_reservation_")
                || name.contains("fk_inventory_reservation_") || name.contains("ck_inventory_reservation_")
                || name.contains("fk_inventory_recipe_use_") || name.contains("ck_inventory_transaction_source")
                || name.contains("uq_recipe_use_reservation") || name.contains("uq_inventory_transaction_idempotency"))
            return new ConflictException("INVENTORY_ALLOCATION_CONFLICT",
                    "Order inventory allocation conflicts with immutable inventory data");
        return null;
    }
}
