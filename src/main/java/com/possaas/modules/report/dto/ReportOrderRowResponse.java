package com.possaas.modules.report.dto;

import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReportOrderRowResponse(
    UUID id,
    String orderCode,
    ServiceType serviceType,
    SourceChannel sourceChannel,
    OrderStatus status,
    OrderPaymentStatus paymentStatus,
    String currencyCode,
    BigDecimal totalAmount,
    BigDecimal collectedAmountInRange,
    Instant createdAt,
    Instant completedAt,
    Instant cancelledAt,
    Instant reportEventAt
) {}
