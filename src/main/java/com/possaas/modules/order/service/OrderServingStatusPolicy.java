package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderItem;
import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.modules.order.entity.OrderStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/** The single source of truth for the serving status of a submitted order. */
@Component
public class OrderServingStatusPolicy {
    public OrderStatus aggregate(Order order, Collection<OrderItem> lines) {
        if (order.getStatus() == OrderStatus.OPEN || order.getStatus() == OrderStatus.COMPLETED
                || order.getStatus() == OrderStatus.CANCELLED) return order.getStatus();
        List<OrderItem> live = lines.stream().filter(line -> line.getStatus() != OrderItemStatus.CANCELLED).toList();
        if (live.isEmpty()) throw new ConflictException("EMPTY_ORDER", "A submitted order must contain a live item");
        if (live.stream().allMatch(line -> line.getStatus() == OrderItemStatus.PENDING)) return OrderStatus.CONFIRMED;
        if (live.stream().allMatch(line -> line.getStatus() == OrderItemStatus.SERVED)) return OrderStatus.SERVED;
        if (live.stream().anyMatch(line -> line.getStatus() == OrderItemStatus.PENDING
                || line.getStatus() == OrderItemStatus.COOKING)) return OrderStatus.PREPARING;
        return OrderStatus.READY;
    }
}
