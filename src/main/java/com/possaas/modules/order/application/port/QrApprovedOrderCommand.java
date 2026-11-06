package com.possaas.modules.order.application.port;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trusted staff approval entry point. This is not exposed by staff HTTP order DTOs. */
public interface QrApprovedOrderCommand {
    Preparation prepare(Intent intent);
    Result persist(Preparation preparation);

    interface Preparation {}
    record Intent(UUID requestId, String requestHash, CurrentUser actor, ServiceType serviceType,
            SourceChannel sourceChannel, UUID tableId, UUID targetSessionId, String currencyCode,
            String customerName, String customerPhone, String note, List<OrderRequests.Line> items,
            List<QuoteLine> reviewedQuote, Instant now, String ip) {
        @Override public String toString() { return "QrApprovedOrderIntent[requestId=" + requestId + ", private fields omitted]"; }
    }
    record QuoteLine(UUID itemId, String name, String unit, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal lineTotal) {}
    record Result(OrderResponse order, List<UUID> createdOrderItemIds) {}
}
