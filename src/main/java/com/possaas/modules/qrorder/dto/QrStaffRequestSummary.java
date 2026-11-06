package com.possaas.modules.qrorder.dto;

import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.qrorder.entity.QrEntryType;
import com.possaas.modules.qrorder.entity.QrRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QrStaffRequestSummary(UUID requestId, QrEntryType entryType, ServiceType serviceType,
        SourceChannel sourceChannel, QrRequestStatus status, long version, boolean expired, UUID tableId,
        UUID observedSessionId, String customerName, String customerPhone, String note, String currencyCode,
        BigDecimal estimatedAmount, BigDecimal approvedAmount, UUID approvedOrderId, UUID approvedSessionId,
        UUID approvedBy, Instant approvedAt, UUID rejectedBy, Instant rejectedAt, String rejectionReason,
        Instant cancelledAt, String cancellationReason, Instant createdAt, Instant expiresAt, List<Line> items) {
    public record Line(UUID id, UUID itemId, String itemName, String unit, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal lineTotal, String note, UUID approvedOrderItemId) {
        @Override public String toString() { return "QrStaffRequestLine[id=" + id + ", private fields omitted]"; }
    }
    @Override public String toString() { return "QrStaffRequestSummary[requestId=" + requestId + ", status=" + status + "]"; }
}
