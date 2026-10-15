package com.possaas.modules.qrorder.service;

import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.qrorder.dto.*;
import com.possaas.modules.qrorder.entity.*;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class QrStaffResponseMapper {
    public QrStaffRequestSummary summary(QrOrderRequest r, List<QrOrderRequestItem> lines, Instant now) {
        return new QrStaffRequestSummary(r.getId(), r.getEntryType(), r.getEntryType().serviceType(), r.getEntryType().sourceChannel(),
            r.getStatus(), r.getVersion(), !r.getExpiresAt().isAfter(now), r.getTableId(), r.getObservedSessionId(),
            r.getCustomerName(), r.getCustomerPhone(), r.getNote(), r.getCurrencyCode(), r.getEstimatedAmount(), r.getApprovedAmount(),
            r.getApprovedOrderId(), r.getApprovedSessionId(), r.getApprovedBy(), r.getApprovedAt(), r.getRejectedBy(), r.getRejectedAt(),
            r.getRejectionReason(), r.getCancelledAt(), r.getCancellationReason(), r.getCreatedAt(), r.getExpiresAt(),
            lines.stream().map(l -> new QrStaffRequestSummary.Line(l.getId(), l.getItemId(), l.getQuotedItemName(), l.getQuotedUnit(),
                l.getQuantity(), l.getQuotedUnitPrice(), l.getQuotedLineTotal(), l.getNote(), l.getApprovedOrderItemId())).toList());
    }
    public QrApprovalResponse approved(QrOrderRequest r, List<QrOrderRequestItem> lines, OrderResponse order, boolean replay) {
        return new QrApprovalResponse(r.getId(), r.getStatus(), r.getVersion(), r.getApprovedOrderId(), r.getApprovedSessionId(),
            r.getApprovedBy(), r.getApprovedAt(), r.getApprovedAmount(),
            lines.stream().map(QrOrderRequestItem::getApprovedOrderItemId).toList(), replay, order);
    }
}
