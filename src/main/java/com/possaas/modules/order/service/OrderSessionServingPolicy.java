package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.OrderRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OrderSessionServingPolicy {
    // Must stay identical to V19's partial unique predicate.
    public static final Set<OrderStatus> SERVING_STATUSES = Set.of(
        OrderStatus.OPEN, OrderStatus.CONFIRMED, OrderStatus.PREPARING,
        OrderStatus.READY, OrderStatus.SERVED
    );
    private final OrderRepository orders;

    public void requireAvailable(UUID tenant, ServiceType type, UUID sessionId) {
        if(type==ServiceType.DINE_IN && orders.existsByRestaurantIdAndTableSessionIdAndServiceTypeAndStatusIn(
                tenant, sessionId, ServiceType.DINE_IN, SERVING_STATUSES))
            throw new ConflictException("TABLE_SESSION_HAS_SERVING_ORDER",
                "Session already has a serving order; append to the existing order");
    }
    public Optional<Order> findServing(UUID tenant, UUID sessionId) {
        var found=orders.findAllByRestaurantIdAndTableSessionIdAndServiceTypeAndStatusIn(
            tenant,sessionId,ServiceType.DINE_IN,SERVING_STATUSES);
        if(found.size()>1) throw new ConflictException("INCONSISTENT_SERVING_ORDER_STATE",
            "Session has inconsistent serving order data; contact an administrator");
        return found.stream().findAny();
    }
}
