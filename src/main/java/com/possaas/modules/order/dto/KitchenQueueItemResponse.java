package com.possaas.modules.order.dto;

import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.modules.order.entity.ServiceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record KitchenQueueItemResponse(UUID orderId, String orderCode, long orderVersion,
        UUID orderItemId, String itemName, BigDecimal quantity, String unit, String note,
        OrderItemStatus itemStatus, ServiceType serviceType, Instant createdAt,
        Instant sentToKitchenAt, UUID sessionId, UUID tableId, String tableCode, String tableName) {}
