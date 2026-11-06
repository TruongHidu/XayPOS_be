package com.possaas.modules.order.dto;

import com.possaas.modules.order.entity.OrderItemStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderItemResponse(UUID id, UUID itemId, String itemName, String unit, BigDecimal quantity,
        BigDecimal unitPrice, BigDecimal discountAmount, BigDecimal lineTotal, OrderItemStatus status,
        String note, String cancelReason, Instant sentToKitchenAt) {}
