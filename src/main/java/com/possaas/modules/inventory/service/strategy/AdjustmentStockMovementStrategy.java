package com.possaas.modules.inventory.service.strategy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.entity.AdjustmentDirection;
import com.possaas.modules.inventory.entity.InventoryTransactionType;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.entity.StockDocumentType;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AdjustmentStockMovementStrategy implements StockMovementStrategy {
    public StockDocumentType supportedType() { return StockDocumentType.ADJUSTMENT; }
    public void validateDocument(StockDocument document) { }
    public void validateLine(StockDocumentItem item) {
        if (item.getAdjustmentDirection() == null)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ADJUSTMENT_DIRECTION",
                    "Adjustment requires adjustmentDirection");
    }
    public BigDecimal calculateQuantityChange(StockDocumentItem item) {
        validateLine(item);
        return item.getAdjustmentDirection() == AdjustmentDirection.INCREASE
                ? item.getQuantity() : item.getQuantity().negate();
    }
    public InventoryTransactionType transactionType(StockDocumentItem item) {
        validateLine(item);
        return item.getAdjustmentDirection() == AdjustmentDirection.INCREASE
                ? InventoryTransactionType.ADJUSTMENT_INCREASE
                : InventoryTransactionType.ADJUSTMENT_DECREASE;
    }
}
