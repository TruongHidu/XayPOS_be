package com.possaas.modules.inventory.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.application.port.TrackedIngredientCatalog.TrackedIngredientSnapshot;
import com.possaas.modules.inventory.entity.AdjustmentDirection;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.entity.StockDocumentType;
import com.possaas.modules.inventory.service.strategy.StockMovementStrategyRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockDocumentItemFactory {
    private final StockDocumentValidationPolicy validation;
    private final StockMovementStrategyRegistry strategies;
    private final Clock clock;

    public StockDocumentItem create(StockDocument document, TrackedIngredientSnapshot ingredient,
            BigDecimal quantity, BigDecimal unitCost, AdjustmentDirection direction, String note) {
        requireIngredientAllowed(document.getDocumentType(), ingredient);
        StockDocumentItem item = new StockDocumentItem();
        item.initialize(document.getRestaurantId(), document.getId(), ingredient.id(), ingredient.sku(),
                ingredient.name(), ingredient.baseUnit(), validation.quantity(quantity), direction,
                validation.cost(unitCost), validation.note(note), clock.instant());
        strategies.get(document.getDocumentType()).validateLine(item);
        return item;
    }

    public boolean update(StockDocument document, StockDocumentItem item, BigDecimal quantity,
            BigDecimal unitCost, AdjustmentDirection direction, String note) {
        BigDecimal normalizedQuantity = validation.quantity(quantity);
        BigDecimal normalizedCost = validation.cost(unitCost);
        String normalizedNote = validation.note(note);
        StockDocumentItem probe = new StockDocumentItem();
        probe.initialize(document.getRestaurantId(), document.getId(), item.getIngredientId(),
                item.getIngredientSku(), item.getIngredientName(), item.getUnit(), normalizedQuantity,
                direction, normalizedCost, normalizedNote, clock.instant());
        strategies.get(document.getDocumentType()).validateLine(probe);
        return item.update(normalizedQuantity, direction, normalizedCost, normalizedNote, clock.instant());
    }

    public void requireIngredientAllowed(StockDocumentType type, TrackedIngredientSnapshot ingredient) {
        if (type == StockDocumentType.RECEIPT && !ingredient.active())
            throw new BusinessException(HttpStatus.CONFLICT, "INGREDIENT_INACTIVE_FOR_RECEIPT",
                    "Inactive ingredient cannot be received");
    }
}
