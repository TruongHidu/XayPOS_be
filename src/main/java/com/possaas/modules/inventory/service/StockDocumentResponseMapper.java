package com.possaas.modules.inventory.service;

import com.possaas.modules.inventory.dto.StockDocumentDetailResponse;
import com.possaas.modules.inventory.dto.StockDocumentItemResponse;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.entity.Warehouse;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StockDocumentResponseMapper {
    public StockDocumentDetailResponse detail(StockDocument document, Warehouse warehouse,
            List<StockDocumentItem> items) {
        return new StockDocumentDetailResponse(
                document.getId(), document.getDocumentCode(), document.getDocumentType(), document.getStatus(),
                warehouse.getId(), warehouse.getCode(), warehouse.getName(), document.getNote(),
                document.getLineCount(), document.getTotalQuantity(), document.getTotalCost(),
                items.stream().map(this::item).toList(), document.getCreatedBy(), document.getConfirmedBy(),
                document.getCancelledBy(), document.getVersion(), document.getCreatedAt(), document.getUpdatedAt(),
                document.getConfirmedAt(), document.getCancelledAt(), document.getStockAuditId(), document.getCurrencyCode());
    }

    private StockDocumentItemResponse item(StockDocumentItem item) {
        return new StockDocumentItemResponse(item.getId(), item.getIngredientId(), item.getIngredientSku(),
                item.getIngredientName(), item.getUnit(), item.getQuantity(), item.getAdjustmentDirection(),
                item.getUnitCost(), item.getTotalCost(), item.getNote());
    }
}
