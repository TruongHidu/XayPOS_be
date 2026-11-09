package com.possaas.modules.order.application.port;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.possaas.common.exception.ConflictException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Port failure of {@link OrderInventoryAllocationCommand#reserve}; details are for staff callers only. */
public class InsufficientStockException extends ConflictException {
    public static final String CODE = "INSUFFICIENT_AVAILABLE_STOCK";
    private final transient Details details;

    public InsufficientStockException(List<ShortageLine> lines) {
        super(CODE, "Insufficient available inventory");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("Shortage lines are required");
        this.details = new Details(lines);
    }

    @Override
    public Details details() {
        return details;
    }

    public record Details(List<ShortageLine> lines) {
        public Details { lines = List.copyOf(lines); }
    }

    /**
     * maxAvailableQuantity assumes every other line of the same command stays unchanged.
     * shortIngredients is null (omitted) when the caller may not read inventory data.
     */
    public record ShortageLine(UUID orderItemId, UUID menuItemId, String itemName, BigDecimal requestedQuantity,
            BigDecimal maxAvailableQuantity, String unit, boolean sharesIngredientWithOtherLines,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<ShortIngredient> shortIngredients) {
        public ShortageLine { shortIngredients = shortIngredients == null ? null : List.copyOf(shortIngredients); }
    }

    /** requiredQuantity is the whole command's demand for this ingredient. */
    public record ShortIngredient(UUID ingredientId, String name, String unit, BigDecimal availableQuantity,
            BigDecimal requiredQuantity) {}
}
