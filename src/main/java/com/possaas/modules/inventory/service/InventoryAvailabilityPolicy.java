package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.inventory.entity.InventoryBalance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class InventoryAvailabilityPolicy {
    private static final BigDecimal MAX = new BigDecimal("99999999999.999");

    public BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX) > 0)
            throw range();
        try { return value.setScale(3, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException invalid) { throw range(); }
    }

    public BigDecimal positive(BigDecimal value) {
        BigDecimal normalized = quantity(value);
        if (normalized.signum() == 0) throw range();
        return normalized;
    }

    public void invariant(InventoryBalance balance, BigDecimal actualReserved) {
        if (balance == null || balance.getQuantity() == null || balance.getReservedQuantity() == null
                || balance.getQuantity().signum() < 0 || balance.getReservedQuantity().signum() < 0
                || balance.getReservedQuantity().compareTo(balance.getQuantity()) > 0
                || balance.getReservedQuantity().compareTo(actualReserved) != 0)
            throw invariant();
        quantity(balance.getQuantity()); quantity(balance.getReservedQuantity());
    }

    public void requireAvailable(InventoryBalance balance, BigDecimal requested) {
        quantity(requested);
        BigDecimal available = balance == null ? BigDecimal.ZERO : balance.getAvailableQuantity();
        if (available.compareTo(requested) < 0)
            throw new ConflictException("INSUFFICIENT_AVAILABLE_STOCK", "Insufficient available inventory");
    }

    public void requireStockPosting(BigDecimal after, BigDecimal reserved) {
        if (after.signum() < 0 && reserved.signum() == 0)
            throw new ConflictException("INSUFFICIENT_STOCK", "Insufficient inventory");
        if (after.compareTo(reserved) < 0)
            throw new ConflictException("INSUFFICIENT_AVAILABLE_STOCK", "Inventory is reserved for orders");
        quantity(after);
    }

    public static ConflictException invariant() {
        return new ConflictException("INVENTORY_RESERVATION_INVARIANT_VIOLATION", "Inventory reservations are inconsistent");
    }

    private static ConflictException range() {
        return new ConflictException("INVENTORY_QUANTITY_OUT_OF_RANGE", "Inventory quantity exceeds supported precision");
    }
}
