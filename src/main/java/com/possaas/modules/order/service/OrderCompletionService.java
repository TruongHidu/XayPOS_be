package com.possaas.modules.order.service;

import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.table.application.port.OrderSessionClosureCommand;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class OrderCompletionService {
    private final OrderAccessService access;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderLifecyclePolicy lifecycle;
    private final OrderCompletionPolicy policy;
    private final OrderSessionClosureCommand sessionClosure;
    private final OrderAudit audit;
    private final OrderResponseMapper mapper;
    private final Clock clock;

    @Transactional
    public OrderResponse complete(UUID id,long expectedVersion,String ip) {
        var locked=access.lock(); var actor=locked.actor();
        if(!actor.hasPermission("ORDER_COMPLETE")) throw new AccessDeniedException("Required completion permission is missing");
        var order=access.order(actor.restaurantId(),id);
        boolean dineIn=order.getServiceType()==ServiceType.DINE_IN;
        boolean sessionClosed=false;
        if(dineIn) {
            policy.requireTableSession(order);
            // TABLE owns tenant/permission/feature/session-state checks, including retries.
            sessionClosed=sessionClosure.isClosed(actor.restaurantId(),order.getTableSessionId());
        }
        var lines=items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(actor.restaurantId(),id);
        if(order.getStatus()==OrderStatus.COMPLETED) {
            if(dineIn && !sessionClosed) {
                // Repair a legacy completed order's exact OPEN session without mutating the order again.
                policy.requireCompletable(order,lines);
                sessionClosure.close(actor.restaurantId(),order.getTableSessionId(),
                    clock.instant().truncatedTo(ChronoUnit.MICROS),ip);
            }
            return mapper.detail(order,lines);
        }
        if(dineIn) policy.requireOpenSession(sessionClosed);
        lifecycle.requireVersion(order,expectedVersion);
        policy.requireCompletable(order,lines);
        var before=audit.snapshot(order,lines);
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        order.setStatus(OrderStatus.COMPLETED); order.setCompletedAt(now); order.changed(now);
        orders.saveAndFlush(order);
        if(dineIn) sessionClosure.close(actor.restaurantId(),order.getTableSessionId(),now,ip);
        audit.record(actor,"ORDER_COMPLETED",order,before,lines,ip);
        orders.flush();
        return mapper.detail(order,lines);
    }
}
