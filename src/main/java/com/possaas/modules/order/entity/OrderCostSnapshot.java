package com.possaas.modules.order.entity;
import java.math.BigDecimal;
/** Immutable acceptance value. Null version is reserved for explicitly represented old history. */
public record OrderCostSnapshot(Short version, OrderCostStatus status, OrderCostUnavailableReason reason,
        BigDecimal unitCost, BigDecimal totalCost) {
    public static OrderCostSnapshot complete(BigDecimal unit, BigDecimal total) {
        return new OrderCostSnapshot((short)1, OrderCostStatus.COMPLETE, null, unit, total);
    }
    public static OrderCostSnapshot incomplete(OrderCostUnavailableReason reason) {
        return new OrderCostSnapshot((short)1, OrderCostStatus.INCOMPLETE, reason, null, null);
    }
    public static OrderCostSnapshot unavailable() {
        return new OrderCostSnapshot((short)1, OrderCostStatus.UNAVAILABLE,
                OrderCostUnavailableReason.RECIPE_FEATURE_DISABLED, null, null);
    }
    public static OrderCostSnapshot legacy() { return new OrderCostSnapshot(null,null,null,null,null); }
}
