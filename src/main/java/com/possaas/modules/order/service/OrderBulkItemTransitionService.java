package com.possaas.modules.order.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.order.entity.OrderItem;
import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderBulkItemTransitionService {
    private final CurrentTenantProvider tenants;
    private final CurrentUserProvider users;
    private final RestaurantRepository restaurants;
    private final FeatureAccessChecker features;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderItemTransitionPolicy submitted;
    private final OrderBulkItemTransitionPolicy policy;
    private final OrderServingStatusPolicy servingStatus;
    private final OrderLifecyclePolicy lifecycle;
    private final OrderHistory history;
    private final OrderAudit audit;
    private final OrderResponseMapper mapper;
    private final Clock clock;
    private final OrderInventoryAllocationOperation inventory;

    @Transactional
    public OrderResponse markReady(UUID orderId, long expectedVersion, String ip) {
        return transition(orderId, expectedVersion, ip, OrderItemStatus.READY,
            "KITCHEN_MARK_READY", "KITCHEN_DISPLAY", "ORDER_ITEMS_MARKED_READY");
    }

    @Transactional
    public OrderResponse markServed(UUID orderId, long expectedVersion, String ip) {
        return transition(orderId, expectedVersion, ip, OrderItemStatus.SERVED,
            "ORDER_MARK_SERVED", "ORDER_MANAGEMENT", "ORDER_ITEMS_MARKED_SERVED");
    }

    private OrderResponse transition(UUID orderId, long expectedVersion, String ip,
            OrderItemStatus target, String permission, String feature, String action) {
        var tenant = tenants.getRequiredRestaurantId();
        var actor = users.getRequired();
        if (!actor.hasPermission(permission))
            throw new AccessDeniedException("Required serving permission is missing");
        // Match all ORDER mutations: restaurant lock before reading order/lines or writing children.
        restaurants.findByIdForUpdate(tenant).orElseThrow(() ->
            new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        features.requireFeature(tenant, feature);
        var order = orders.findByIdAndRestaurantId(orderId, tenant).orElseThrow(() ->
            new ResourceNotFoundException("ORDER_NOT_FOUND", "Order not found"));
        submitted.requireSubmitted(order);
        var lines = items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(tenant, orderId);
        var changed = target == OrderItemStatus.READY ? policy.readyItems(lines) : policy.servedItems(lines);
        inventory.validate(order, lines);
        // A retry after the goal is already satisfied is harmless, even with the original version.
        if (changed.isEmpty()) return mapper.detail(order, lines);
        lifecycle.requireVersion(order, expectedVersion);
        var before = audit.snapshot(order, lines);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (target == OrderItemStatus.SERVED)
            inventory.consume(order, changed.stream().filter(line -> line.getStatus() == OrderItemStatus.PENDING).toList(), actor, now, ip);
        // Capture real source states before mutation; direct serving may contain several states.
        var byPreviousStatus = changed.stream().collect(Collectors.groupingBy(
            OrderItem::getStatus, () -> new EnumMap<>(OrderItemStatus.class), Collectors.toList()));
        for (var line : changed) {
            line.setStatus(target);
            line.setUpdatedAt(now);
        }
        order.setStatus(servingStatus.aggregate(order, lines));
        order.changed(now);
        items.saveAll(changed);
        orders.saveAndFlush(order);
        byPreviousStatus.forEach((source, affectedLines) ->
            history.transitioned(order, affectedLines, actor.userId(), source, target, now));
        audit.record(actor, action, order, before, lines, ip);
        orders.flush();
        return mapper.detail(order, lines);
    }
}
