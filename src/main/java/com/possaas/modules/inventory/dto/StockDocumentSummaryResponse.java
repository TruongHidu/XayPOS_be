package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.StockDocumentStatus;
import com.possaas.modules.inventory.entity.StockDocumentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StockDocumentSummaryResponse(
        UUID id,
        String documentCode,
        StockDocumentType documentType,
        StockDocumentStatus status,
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        String note,
        int lineCount,
        BigDecimal totalQuantity,
        BigDecimal totalCost,
        UUID createdBy,
        long version,
        Instant createdAt,
        Instant updatedAt,
        UUID stockAuditId, String currencyCode) {}
