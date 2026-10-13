package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderItem;
import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import java.util.Collection;
import org.springframework.stereotype.Component;

@Component
public class OrderCompletionPolicy {
    public void requireCompletable(Order order,Collection<OrderItem> lines) {
        if(order.getStatus()==OrderStatus.CANCELLED || order.getStatus()==OrderStatus.OPEN)
            throw new ConflictException("ORDER_NOT_READY_FOR_COMPLETION","Order is not ready for completion");
        if(order.getPaymentStatus()!=OrderPaymentStatus.PAID || order.getTotalAmount().signum()<=0
                || order.getPaidAmount().compareTo(order.getTotalAmount())!=0)
            throw new ConflictException("ORDER_NOT_PAID","Order must be paid in full before completion");
        var live=lines.stream().filter(line -> line.getStatus()!=OrderItemStatus.CANCELLED).toList();
        if(live.isEmpty() || live.stream().anyMatch(line -> line.getStatus()!=OrderItemStatus.SERVED))
            throw new ConflictException("ORDER_NOT_READY_FOR_COMPLETION","All live items must be served");
    }
}
