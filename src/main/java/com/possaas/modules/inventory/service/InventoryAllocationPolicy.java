package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.inventory.application.port.RecipeUsageCostQuery.UsageCost;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.AcceptedItem;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.LineState;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.Usage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class InventoryAllocationPolicy {
    public InventoryAllocationSkipReason skipReason(AcceptedItem item, boolean enabled) {
        if ("LEGACY_UNAVAILABLE".equals(item.snapshotStatus())) return InventoryAllocationSkipReason.LEGACY_UNAVAILABLE;
        if ("RECIPE_DISABLED".equals(item.snapshotStatus())) return InventoryAllocationSkipReason.SNAPSHOT_RECIPE_DISABLED;
        if (!enabled) return InventoryAllocationSkipReason.INVENTORY_FEATURE_DISABLED;
        return switch (item.snapshotStatus()) {
            case "NO_RECIPE" -> InventoryAllocationSkipReason.SNAPSHOT_NO_RECIPE;
            case "FEATURE_DISABLED" -> InventoryAllocationSkipReason.SNAPSHOT_FEATURE_DISABLED;
            case "CAPTURED" -> item.usages().isEmpty() ? InventoryAllocationSkipReason.NO_TRACKED_INGREDIENTS : null;
            default -> throw conflict();
        };
    }

    public void matches(OrderItemInventoryAllocation allocation, UUID tenant, UUID order, AcceptedItem item) {
        if (!allocation.getRestaurantId().equals(tenant) || !allocation.getOrderId().equals(order)
                || !allocation.getOrderItemId().equals(item.orderItemId())
                || !Objects.equals(allocation.getRecipeSnapshotId(), item.recipeSnapshotId())
                || allocation.getOrderQuantitySnapshot().compareTo(item.orderQuantity()) != 0
                || !allocation.getOrderUnitSnapshot().equals(item.orderUnit())) throw conflict();
    }

    public void matchesUsages(OrderItemInventoryAllocation allocation, AcceptedItem item,
            List<InventoryReservation> details) {
        if (allocation.getStatus() == InventoryAllocationStatus.SKIPPED) {
            if (!details.isEmpty()) throw conflict();
            return;
        }
        if (details.size() != item.usages().size()) throw conflict();
        Map<UUID, Usage> expected = new HashMap<>();
        item.usages().forEach(usage -> expected.put(usage.recipeUsageId(), usage));
        for (InventoryReservation detail : details) {
            var usage = expected.get(detail.getRecipeUsageId());
            if (usage == null || !usage.ingredientId().equals(detail.getIngredientId())
                    || usage.quantity().compareTo(detail.getQuantity()) != 0
                    || !Objects.equals(usage.sku(), detail.getIngredientSkuSnapshot())
                    || !usage.name().equals(detail.getIngredientNameSnapshot())
                    || !usage.unit().equals(detail.getIngredientUnitSnapshot())) throw conflict();
        }
    }

    public void line(OrderItemInventoryAllocation allocation, LineState line) {
        if (!line.submitted()) {
            if (allocation != null) throw conflict();
            return;
        }
        if (allocation == null) throw missing();
        if (allocation.getOrderQuantitySnapshot().compareTo(line.quantity()) != 0
                || !allocation.getOrderUnitSnapshot().equals(line.unit())) throw conflict();
        boolean valid = switch (line.status()) {
            case "PENDING" -> allocation.getStatus() == InventoryAllocationStatus.RESERVED;
            case "COOKING", "READY", "SERVED" -> allocation.getStatus() == InventoryAllocationStatus.CONSUMED;
            case "CANCELLED" -> allocation.getStatus() == InventoryAllocationStatus.RELEASED;
            default -> false;
        };
        if (!valid && allocation.getStatus() != InventoryAllocationStatus.SKIPPED) throw transition();
    }

    public void history(OrderItemInventoryAllocation allocation, List<InventoryReservation> details,
            Map<UUID, InventoryTransaction> ledger, Map<UUID, UsageCost> costs) {
        if (allocation.getStatus() == InventoryAllocationStatus.SKIPPED) {
            if (!details.isEmpty() || allocation.getWarehouseId() != null || allocation.getSkipReason() == null)
                throw conflict();
            return;
        }
        if (details.isEmpty() || allocation.getWarehouseId() == null || allocation.getSkipReason() != null)
            throw conflict();
        if (costs == null) throw conflict();
        Set<UUID> ingredients = new HashSet<>();
        Set<UUID> usages = new HashSet<>();
        for (InventoryReservation detail : details) {
            if (!allocation.getId().equals(detail.getAllocationId())
                    || !allocation.getRestaurantId().equals(detail.getRestaurantId())
                    || !allocation.getRecipeSnapshotId().equals(detail.getRecipeSnapshotId())
                    || !allocation.getWarehouseId().equals(detail.getWarehouseId())
                    || detail.getQuantity().signum() <= 0 || !ingredients.add(detail.getIngredientId())
                    || !usages.add(detail.getRecipeUsageId())) throw conflict();
            BigDecimal expectedCost = usageUnitCost(allocation, detail, costs.get(detail.getRecipeUsageId()));
            InventoryTransaction transaction = ledger.get(detail.getId());
            if (allocation.getStatus() != InventoryAllocationStatus.CONSUMED) {
                if (transaction != null) throw conflict();
            } else if (transaction == null || transaction.getSourceType() != InventorySourceType.ORDER_ITEM
                    || transaction.getTransactionType() != InventoryTransactionType.RECIPE_USE
                    || !allocation.getRestaurantId().equals(transaction.getRestaurantId())
                    || !allocation.getOrderId().equals(transaction.getOrderId())
                    || !allocation.getOrderItemId().equals(transaction.getOrderItemId())
                    || !detail.getWarehouseId().equals(transaction.getWarehouseId())
                    || !detail.getIngredientId().equals(transaction.getItemId())
                    || !detail.getId().equals(transaction.getReservationId())
                    || !detail.getRecipeUsageId().equals(transaction.getRecipeUsageId())
                    || transaction.getQuantityChange().compareTo(detail.getQuantity().negate()) != 0
                    || !detail.getIngredientUnitSnapshot().equals(transaction.getUnit())
                    || !sameCost(expectedCost, transaction.getUnitCost()) || transaction.getStockDocumentId() != null
                    || transaction.getStockDocumentItemId() != null
                    || transaction.getBeforeQuantity().add(transaction.getQuantityChange())
                        .compareTo(transaction.getAfterQuantity()) != 0) throw conflict();
        }
    }

    /** The usage decision is authoritative even when another usage made its header incomplete. */
    public BigDecimal usageUnitCost(OrderItemInventoryAllocation allocation, InventoryReservation reservation,
            UsageCost cost) {
        if (cost == null || !allocation.getOrderId().equals(cost.orderId())
                || !allocation.getOrderItemId().equals(cost.orderItemId())
                || !allocation.getRecipeSnapshotId().equals(cost.recipeSnapshotId())
                || !reservation.getRecipeUsageId().equals(cost.recipeUsageId())
                || !reservation.getIngredientId().equals(cost.ingredientId())
                || !reservation.getIngredientUnitSnapshot().equals(cost.unit())
                || cost.quantity() == null || reservation.getQuantity().compareTo(cost.quantity()) != 0)
            throw conflict();
        if (cost.costSnapshotVersion() == null) {
            if (cost.costConfigured() != null || cost.unitCost() != null) throw conflict();
            return null;
        }
        if (cost.costSnapshotVersion() != 1 || cost.costConfigured() == null) throw conflict();
        if (!cost.costConfigured()) {
            if (cost.unitCost() != null) throw conflict();
            return null;
        }
        if (cost.unitCost() == null || cost.unitCost().signum() < 0) throw conflict();
        try {
            if (cost.unitCost().setScale(2, RoundingMode.UNNECESSARY).precision() > 14) throw conflict();
        } catch (ArithmeticException invalidPrecision) {
            throw conflict();
        }
        return cost.unitCost();
    }

    private boolean sameCost(BigDecimal expected, BigDecimal actual) {
        return expected == null ? actual == null : actual != null && expected.compareTo(actual) == 0;
    }

    public static ConflictException conflict() {
        return new ConflictException("INVENTORY_ALLOCATION_CONFLICT", "Order inventory allocation is inconsistent");
    }
    public static ConflictException missing() {
        return new ConflictException("INVENTORY_ALLOCATION_NOT_FOUND", "Submitted order item has no inventory allocation");
    }
    public static ConflictException transition() {
        return new ConflictException("INVALID_INVENTORY_ALLOCATION_TRANSITION", "Inventory allocation cannot make this transition");
    }
}
