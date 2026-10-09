package com.possaas.modules.order.service;

import com.possaas.modules.order.dto.*;
import com.possaas.modules.order.entity.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OrderResponseMapper {
    private BigDecimal amount(BigDecimal value) { return value.setScale(2); }
    public OrderResponse detail(Order o,List<OrderItem> lines) {
        return new OrderResponse(o.getId(),o.getOrderCode(),o.getServiceType(),o.getSourceChannel(),o.getTableSessionId(),o.getStatus(),o.getPaymentStatus(),
                o.getCustomerName(),o.getCustomerPhone(),o.getGuestCount(),o.getNote(),o.getCurrencyCode(),amount(o.getSubtotalAmount()),amount(o.getDiscountAmount()),
                amount(o.getTaxAmount()),amount(o.getServiceChargeAmount()),amount(o.getTotalAmount()),amount(o.getPaidAmount()),o.getCreatedBy(),o.getConfirmedAt(),
                o.getCompletedAt(),o.getCancelledAt(),o.getCancelReason(),o.getCreatedAt(),o.getUpdatedAt(),o.getVersion(),lines.stream().map(this::item).toList());
    }
    public OrderItemResponse item(OrderItem l) {
        return new OrderItemResponse(l.getId(),l.getItemId(),l.getItemName(),l.getUnit(),l.getQuantity(),amount(l.getUnitPrice()),amount(l.getDiscountAmount()),
                amount(l.getLineTotal()),l.getStatus(),l.getNote(),l.getCancelReason(),l.getSentToKitchenAt());
    }
    public OrderSummaryResponse summary(Order o) {
        return new OrderSummaryResponse(o.getId(),o.getOrderCode(),o.getServiceType(),o.getSourceChannel(),o.getTableSessionId(),o.getStatus(),o.getPaymentStatus(),
                o.getCustomerName(),o.getGuestCount(),o.getCurrencyCode(),amount(o.getTotalAmount()),amount(o.getPaidAmount()),o.getCreatedAt(),o.getUpdatedAt(),o.getVersion());
    }
}
