package com.possaas.modules.inventory.service.strategy;

import com.possaas.modules.inventory.entity.InventoryTransactionType;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.entity.StockDocumentType;
import java.math.BigDecimal;

public interface StockMovementStrategy {
    StockDocumentType supportedType();
    void validateDocument(StockDocument document);
    void validateLine(StockDocumentItem item);
    BigDecimal calculateQuantityChange(StockDocumentItem item);
    InventoryTransactionType transactionType(StockDocumentItem item);
}
