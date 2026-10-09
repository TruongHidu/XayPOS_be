package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.menu.application.port.OrderMenuQuery;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Core confirmation is synchronous and must join the caller's transaction. */
@Component @RequiredArgsConstructor
public class OrderConfirmationOperation {
    private final OrderLifecyclePolicy lifecycle;
    private final OrderMenuQuery menu;
    private final OrderMoneyCalculator money;
    private final OrderRepository orders;
    private final OrderAudit audit;

    @Transactional(propagation=Propagation.MANDATORY)
    public void confirm(Order order,List<OrderItem> lines,CurrentUser actor,Instant now,String ip) {
        validate(order,lines,actor);
        var before=audit.snapshot(order,lines);
        order.setStatus(OrderStatus.CONFIRMED); order.setConfirmedAt(now);
        money.recalculate(order,lines); order.changed(now); orders.saveAndFlush(order);
        audit.record(actor,"ORDER_CONFIRMED",order,before,lines,ip); orders.flush();
    }
    /** Read-only preparation check; final confirmation also rechecks defensively. */
    public void validate(Order order,List<OrderItem> lines,CurrentUser actor) {
        lifecycle.requireEditable(order);
        var live=lines.stream().filter(l->l.getStatus()!=OrderItemStatus.CANCELLED).toList();
        if(live.isEmpty()) throw new ConflictException("EMPTY_ORDER","Cannot confirm an empty order");
        if(live.stream().anyMatch(l->l.getItemId()==null))
            throw new ConflictException("ITEM_NOT_SELLABLE","Original menu item is unavailable");
        menu.requireSellable(actor.restaurantId(),live.stream().map(OrderItem::getItemId).toList());
    }
}
