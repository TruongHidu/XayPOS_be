package com.possaas.modules.order.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.order.application.port.PayableOrderQuery;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.repository.OrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PayableOrderQueryAdapter implements PayableOrderQuery {
    private final OrderRepository orders;
    private final OrderPaymentPolicy policy;

    @Override
    @Transactional(readOnly = true)
    public PayableOrder requirePayable(UUID restaurantId, UUID orderId) {
        Order order = orders.findByIdAndRestaurantId(orderId, restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND", "Order not found"));
        policy.requirePayable(order);
        return new PayableOrder(order.getId(), order.getOrderCode(), order.getTotalAmount(),
            order.getCurrencyCode(), order.getVersion());
    }
}
