package com.possaas.modules.inventory.service.strategy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.entity.InventoryTransactionType;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.entity.StockDocumentType;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class IssueStockMovementStrategy implements StockMovementStrategy {
    public StockDocumentType supportedType() { return StockDocumentType.ISSUE; }
    public void validateDocument(StockDocument document) { }
    public void validateLine(StockDocumentItem item) {
        if (item.getAdjustmentDirection() != null)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ADJUSTMENT_DIRECTION",
                    "Issue does not accept adjustmentDirection");
    }
    public BigDecimal calculateQuantityChange(StockDocumentItem item) { validateLine(item); return item.getQuantity().negate(); }
    public InventoryTransactionType transactionType(StockDocumentItem item) { return InventoryTransactionType.ISSUE; }
}
