package com.possaas.modules.qrorder.dto;

import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.qrorder.service.QrQuoteService.QrQuote;
import java.math.BigDecimal;
import java.util.UUID;

public record QrStaffRequestDetail(QrStaffRequestSummary request, QrQuote currentQuote,
        String reviewContextHash, boolean canApprove, String reasonCode, ReviewContext reviewContext) {
    public record ReviewContext(UUID currentSessionId, Long tableAccessEpoch, Long sessionVersion, Short guestCount, ServingOrder currentOrder) {}
    public record ServingOrder(UUID id, long version, OrderStatus status, OrderPaymentStatus paymentStatus,
            String currencyCode, BigDecimal totalAmount, BigDecimal paidAmount) {}
    @Override public String toString() { return "QrStaffRequestDetail[requestId=" + request.requestId() + ", private fields omitted]"; }
}
