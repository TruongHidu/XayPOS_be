package com.possaas.modules.order.dto;

import com.possaas.modules.order.entity.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID id, String orderCode, ServiceType serviceType, SourceChannel sourceChannel,
        UUID tableSessionId, OrderStatus status, OrderPaymentStatus paymentStatus, String customerName,
        String customerPhone, short guestCount, String note, String currencyCode, BigDecimal subtotalAmount,
        BigDecimal discountAmount, BigDecimal taxAmount, BigDecimal serviceChargeAmount, BigDecimal totalAmount,
        BigDecimal paidAmount, UUID createdBy, Instant confirmedAt, Instant completedAt, Instant cancelledAt,
        String cancelReason, Instant createdAt, Instant updatedAt, long version, List<OrderItemResponse> items) {}
