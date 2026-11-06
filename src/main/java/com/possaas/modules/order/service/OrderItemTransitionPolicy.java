package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.modules.order.entity.OrderStatus;
import java.util.EnumSet;
import org.springframework.stereotype.Component;

@Component
public class OrderItemTransitionPolicy {
    private static final EnumSet<OrderStatus> SUBMITTED = EnumSet.of(
        OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.SERVED);

    public void requireSubmitted(Order order) {
        if (!SUBMITTED.contains(order.getStatus()))
            throw new ConflictException("INVALID_ORDER_ITEM_TRANSITION", "Order is not being served");
    }

    /** An exact retry is harmless; a line that advanced further is a conflict. */
    public boolean requireTransition(OrderItemStatus current, OrderItemStatus source, OrderItemStatus target) {
        if (current == target) return false;
        if (current != source)
            throw new ConflictException("INVALID_ORDER_ITEM_TRANSITION", "Order item cannot make this transition");
        return true;
    }
}
