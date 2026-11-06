package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.StockAuditStatus;
import java.time.Instant;
import java.util.UUID;

public record StockAuditSummaryResponse(UUID id,String auditCode,UUID warehouseId,String warehouseCode,
        String warehouseName,StockAuditStatus status,String note,long itemCount,long countedItemCount,
        long changedItemCount,UUID adjustmentDocumentId,String adjustmentDocumentCode,long version,
        Instant createdAt,Instant startedAt,Instant updatedAt) {}
