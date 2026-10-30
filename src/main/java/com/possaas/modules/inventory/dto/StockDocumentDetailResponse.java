package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.StockDocumentStatus;
import com.possaas.modules.inventory.entity.StockDocumentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StockDocumentDetailResponse(
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
        List<StockDocumentItemResponse> items,
        UUID createdBy,
        UUID confirmedBy,
        UUID cancelledBy,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant confirmedAt,
        Instant cancelledAt,
        UUID stockAuditId, String currencyCode) {}
