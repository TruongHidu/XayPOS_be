package com.possaas.modules.order.dto;
import com.possaas.modules.order.entity.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record OrderCostSnapshotResponse(UUID orderId,UUID orderItemId,Short costSnapshotVersion,String costBasis,
        OrderCostSource costSource,OrderCostStatus costStatus,OrderCostUnavailableReason reason,
        BigDecimal orderQuantity,String orderUnit,String currencyCode,BigDecimal unitCost,
        BigDecimal totalCost,Instant costCapturedAt) {}
