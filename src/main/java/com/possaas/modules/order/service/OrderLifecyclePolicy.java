package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.entity.*;
import java.util.Collection;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class OrderLifecyclePolicy {
    public void requireVersion(Order order,long expected) {
        if(order.getVersion()!=expected) throw new ConflictException("CONCURRENT_ORDER_UPDATE","Order changed; reload before retrying");
    }
    public void requireEditable(Order order) {
        if(order.getStatus()!=OrderStatus.OPEN) throw new ConflictException("ORDER_NOT_EDITABLE","Only OPEN orders can be edited");
        requireUnpaid(order);
    }
    public void requireUnpaid(Order order) {
        if(order.getPaymentStatus()!=OrderPaymentStatus.UNPAID || order.getPaidAmount().signum()!=0)
            throw new ConflictException("ORDER_HAS_FINANCIAL_OBLIGATIONS","Order has payment obligations");
    }
    public void requireCancellable(Order order,Collection<OrderItem> lines) {
        requireCancellationState(order);
        if(lines.stream().anyMatch(l->l.getStatus()!=OrderItemStatus.PENDING && l.getStatus()!=OrderItemStatus.CANCELLED))
            throw new ConflictException("ORDER_ITEM_NOT_EDITABLE","Order contains cooking or fulfilled items");
    }
    public void requireCancellationState(Order order) {
        if(order.getStatus()!=OrderStatus.OPEN && order.getStatus()!=OrderStatus.CONFIRMED)
            throw new ConflictException("INVALID_ORDER_TRANSITION","Only OPEN or CONFIRMED orders can be cancelled");
        requireUnpaid(order);
    }
    public void requirePending(OrderItem line) {
        if(line.getStatus()!=OrderItemStatus.PENDING) throw new ConflictException("ORDER_ITEM_NOT_EDITABLE","Only PENDING lines can be changed");
    }
    public void requireLineCancellationPermission(Order order,CurrentUser actor) {
        // confirmedAt remains set after cancellation, so idempotent retries keep the original authorization boundary.
        String required=order.getConfirmedAt()==null?"ORDER_UPDATE":"ORDER_CANCEL";
        if(!actor.hasPermission(required)) throw new AccessDeniedException("Required order cancellation permission is missing");
    }
}
