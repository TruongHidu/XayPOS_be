package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.OrderItem;
import com.possaas.modules.order.entity.OrderItemStatus;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates the entire batch before selecting lines; never mutates the
 * aggregate.
 */
@Component
public class OrderBulkItemTransitionPolicy {
    public List<OrderItem> readyItems(Collection<OrderItem> lines) {
        // PENDING lines skip COOKING; the service consumes their stock like start-cooking would.
        return select(lines, Set.of(OrderItemStatus.PENDING, OrderItemStatus.COOKING),
                Set.of(OrderItemStatus.PENDING, OrderItemStatus.COOKING, OrderItemStatus.READY, OrderItemStatus.SERVED),
                "Order item cannot be marked as ready");
    }

    public List<OrderItem> servedItems(Collection<OrderItem> lines) {
        var live = lines.stream()
                .filter(line -> line.getStatus() != OrderItemStatus.CANCELLED)
                .toList();

        if (live.isEmpty()) {
            throw new ConflictException(
                    "EMPTY_ORDER",
                    "A submitted order must contain a live item");
        }

        var allowed = Set.of(
                OrderItemStatus.PENDING,
                OrderItemStatus.COOKING,
                OrderItemStatus.READY,
                OrderItemStatus.SERVED);

        if (live.stream().anyMatch(line -> !allowed.contains(line.getStatus()))) {
            throw new ConflictException(
                    "INVALID_ORDER_ITEM_TRANSITION",
                    "Order item cannot be marked as served");
        }

        return live.stream()
                .filter(line -> line.getStatus() != OrderItemStatus.SERVED)
                .toList();
    }

    private List<OrderItem> select(Collection<OrderItem> lines, Set<OrderItemStatus> sources,
            Set<OrderItemStatus> allowed, String message) {
        var live = lines.stream().filter(line -> line.getStatus() != OrderItemStatus.CANCELLED).toList();
        if (live.isEmpty())
            throw new ConflictException("EMPTY_ORDER", "A submitted order must contain a live item");
        if (live.stream().anyMatch(line -> !allowed.contains(line.getStatus())))
            throw new ConflictException("INVALID_ORDER_ITEM_TRANSITION", message);
        return live.stream().filter(line -> sources.contains(line.getStatus())).toList();
    }
}
