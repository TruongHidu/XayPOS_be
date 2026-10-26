package com.possaas.modules.order.event;

import com.possaas.modules.order.entity.OrderStatus;
import java.time.Instant;
import java.util.*;

/** Snapshot of a completed ORDER mutation, with no persistence or transport dependencies. */
public record OrderChanged(UUID eventId, UUID restaurantId, UUID orderId, long version, Instant occurredAt,
        OrderStatus status, UUID tableSessionId, Set<Change> changes, List<UUID> changedOrderItemIds,
        List<UUID> newKitchenItemIds, boolean kitchenRelevant) {
    public enum Change { CREATED, UPDATED, SUBMITTED, ITEMS_ADDED, ITEM_UPDATED, ITEM_CANCELLED,
        ITEM_STATUS_CHANGED, CANCELLED, COMPLETED }
    public OrderChanged {
        Objects.requireNonNull(eventId); Objects.requireNonNull(restaurantId); Objects.requireNonNull(orderId);
        Objects.requireNonNull(occurredAt); Objects.requireNonNull(status);
        changes = Collections.unmodifiableSet(EnumSet.copyOf(changes));
        changedOrderItemIds = List.copyOf(changedOrderItemIds); newKitchenItemIds = List.copyOf(newKitchenItemIds);
    }
}
