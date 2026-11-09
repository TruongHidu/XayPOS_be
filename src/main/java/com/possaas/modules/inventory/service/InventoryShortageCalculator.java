package com.possaas.modules.inventory.service;

import com.possaas.modules.order.application.port.InsufficientStockException.ShortIngredient;
import com.possaas.modules.order.application.port.InsufficientStockException.ShortageLine;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.AcceptedItem;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.Usage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Pure explanation of a failed reservation: which lines are short and how much of each could still be sold,
 * assuming the other lines of the same command stay unchanged. Never reads or writes persistence.
 */
@Component @RequiredArgsConstructor
public class InventoryShortageCalculator {
    private static final BigDecimal ZERO = new BigDecimal("0.000");
    private final InventoryAvailabilityPolicy quantities;

    /**
     * @param reservable lines that would reserve stock, in request order; one warehouse per command.
     * @param includeIngredients false omits shortIngredients for callers without inventory read access.
     */
    public List<ShortageLine> shortages(List<AcceptedItem> reservable, Map<UUID, BigDecimal> availableByIngredient,
            boolean includeIngredients) {
        Map<UUID, BigDecimal> demand = new HashMap<>();
        for (AcceptedItem item : reservable)
            for (Usage usage : item.usages())
                demand.merge(usage.ingredientId(), quantities.positive(usage.quantity()), BigDecimal::add);
        Set<UUID> shortIngredients = new HashSet<>();
        demand.forEach((ingredient, required) -> {
            if (available(availableByIngredient, ingredient).compareTo(required) < 0) shortIngredients.add(ingredient);
        });
        if (shortIngredients.isEmpty()) return List.of();
        List<ShortageLine> lines = new ArrayList<>();
        for (AcceptedItem item : reservable) {
            List<ShortIngredient> missing = new ArrayList<>();
            BigDecimal ordered = quantities.positive(item.orderQuantity());
            BigDecimal max = ordered;
            boolean shared = false;
            for (Usage usage : item.usages()) {
                UUID ingredient = usage.ingredientId();
                BigDecimal own = quantities.positive(usage.quantity());
                BigDecimal available = available(availableByIngredient, ingredient);
                BigDecimal others = demand.get(ingredient).subtract(own);
                max = max.min(maxFor(ordered, own, available.subtract(others).max(ZERO)));
                if (!shortIngredients.contains(ingredient)) continue;
                shared |= others.signum() > 0;
                missing.add(new ShortIngredient(ingredient, usage.name(), usage.unit(), available,
                        quantities.quantity(demand.get(ingredient))));
            }
            if (missing.isEmpty()) continue;
            lines.add(new ShortageLine(item.orderItemId(), item.menuItemId(), item.itemName(), ordered,
                    quantities.quantity(max), item.orderUnit(), shared, includeIngredients ? missing : null));
        }
        return List.copyOf(lines);
    }

    /**
     * Largest quantity whose proportional usage fits in remaining. Snapshot usages are rounded with CEILING, so
     * own/ordered is never below the true per-unit usage and the result never overstates what can be sold.
     */
    private BigDecimal maxFor(BigDecimal ordered, BigDecimal own, BigDecimal remaining) {
        int scale = ordered.stripTrailingZeros().scale() <= 0 ? 0 : 3;
        return remaining.multiply(ordered).divide(own, scale, RoundingMode.FLOOR);
    }

    private BigDecimal available(Map<UUID, BigDecimal> availableByIngredient, UUID ingredient) {
        return availableByIngredient.getOrDefault(ingredient, ZERO);
    }
}
