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
public class ReceiptStockMovementStrategy implements StockMovementStrategy {
    public StockDocumentType supportedType() { return StockDocumentType.RECEIPT; }
    public void validateDocument(StockDocument document) { }
    public void validateLine(StockDocumentItem item) {
        if (item.getAdjustmentDirection() != null)
            throw invalid("Receipt does not accept adjustmentDirection");
    }
    public BigDecimal calculateQuantityChange(StockDocumentItem item) { validateLine(item); return item.getQuantity(); }
    public InventoryTransactionType transactionType(StockDocumentItem item) { return InventoryTransactionType.RECEIPT; }
    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ADJUSTMENT_DIRECTION", message);
    }
}
