package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.StockAuditStatus;
import java.time.Instant;
import java.util.UUID;

public record StockAuditDetailResponse(UUID id,String auditCode,UUID warehouseId,String warehouseCode,
        String warehouseName,StockAuditStatus status,String note,long itemCount,long countedItemCount,
        long changedItemCount,UUID adjustmentDocumentId,String adjustmentDocumentCode,long version,
        UUID createdBy,UUID startedBy,UUID confirmedBy,UUID cancelledBy,
        Instant createdAt,Instant startedAt,Instant confirmedAt,Instant cancelledAt,Instant updatedAt) {
    public StockAuditSummaryResponse summary(){return new StockAuditSummaryResponse(id,auditCode,warehouseId,
        warehouseCode,warehouseName,status,note,itemCount,countedItemCount,changedItemCount,
        adjustmentDocumentId,adjustmentDocumentCode,version,createdAt,startedAt,updatedAt);}
}
