package com.possaas.modules.qrorder.dto;

import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.qrorder.entity.QrRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Private staff response. Guest projection never contains the complete root order. */
public record QrApprovalResponse(UUID requestId, QrRequestStatus status, long version, UUID approvedOrderId,
        UUID approvedSessionId, UUID approvedBy, Instant approvedAt, BigDecimal approvedAmount,
        List<UUID> approvedOrderItemIds, boolean replay, OrderResponse order) {
    @Override public String toString() { return "QrApprovalResponse[requestId=" + requestId + ", private fields omitted]"; }
}
