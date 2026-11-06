package com.possaas.modules.order.dto;

import com.possaas.modules.order.entity.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderSummaryResponse(UUID id, String orderCode, ServiceType serviceType, SourceChannel sourceChannel,
        UUID tableSessionId, OrderStatus status, OrderPaymentStatus paymentStatus, String customerName, short guestCount,
        String currencyCode, BigDecimal totalAmount, BigDecimal paidAmount, Instant createdAt, Instant updatedAt, long version) {}
