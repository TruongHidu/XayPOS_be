package com.possaas.modules.order.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.order.application.port.OrderPaymentCommand;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class OrderPaymentCommandAdapter implements OrderPaymentCommand {
    private final OrderRepository orders;
    private final OrderPaymentPolicy policy;

    @Override
    public void requireOwned(UUID restaurantId, UUID orderId) {
        owned(restaurantId, orderId);
    }

    @Override public UUID tableSessionId(UUID restaurantId, UUID orderId) {
        return owned(restaurantId, orderId).getTableSessionId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Settlement settleFull(UUID restaurantId, UUID orderId, long expectedVersion,
                                 BigDecimal amount, Instant now) {
        Order order = owned(restaurantId, orderId);
        policy.requireFullSettlement(order, expectedVersion, amount);
        order.setPaidAmount(order.getTotalAmount());
        order.setPaymentStatus(OrderPaymentStatus.PAID);
        order.changed(now);
        orders.saveAndFlush(order);
        return new Settlement(order.getId(), order.getTotalAmount(), order.getCurrencyCode(), order.getVersion());
    }

    private Order owned(UUID restaurantId, UUID orderId) {
        return orders.findByIdAndRestaurantId(orderId, restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND", "Order not found"));
    }
}
