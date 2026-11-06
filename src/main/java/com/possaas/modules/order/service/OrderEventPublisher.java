package com.possaas.modules.order.service;

import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.event.OrderChanged;
import com.possaas.modules.order.event.OrderChanged.Change;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Call once at the command's final flush, never inside shared confirmation or persistence callbacks. */
@Component @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class OrderEventPublisher {
    private final ApplicationEventPublisher events;
    private final Clock clock;
    public void publish(Order order, Set<Change> changes, Collection<OrderItem> changed, Collection<UUID> newKitchenIds) {
        boolean kitchenRelevant = !newKitchenIds.isEmpty() || order.getConfirmedAt() != null;
        events.publishEvent(new OrderChanged(UUID.randomUUID(), order.getRestaurantId(), order.getId(),
                order.getVersion(), clock.instant(), order.getStatus(), order.getTableSessionId(), changes,
                changed.stream().map(OrderItem::getId).distinct().toList(), List.copyOf(newKitchenIds), kitchenRelevant));
    }
    public static List<UUID> unsent(List<OrderItem> lines) {
        return lines.stream().filter(l -> l.getStatus() == OrderItemStatus.PENDING && l.getSentToKitchenAt() == null)
                .map(OrderItem::getId).toList();
    }
}
