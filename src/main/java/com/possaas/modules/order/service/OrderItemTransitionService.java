package com.possaas.modules.order.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class OrderItemTransitionService {
    private final CurrentTenantProvider tenants;
    private final CurrentUserProvider users;
    private final RestaurantRepository restaurants;
    private final FeatureAccessChecker features;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderItemTransitionPolicy policy;
    private final OrderServingStatusPolicy servingStatus;
    private final OrderLifecyclePolicy lifecycle;
    private final OrderHistory history;
    private final OrderAudit audit;
    private final OrderResponseMapper mapper;
    private final Clock clock;

    @Transactional
    public OrderResponse transition(UUID orderId,UUID itemId,long expectedVersion,
            OrderItemStatus source,OrderItemStatus target,String permission,String feature,String ip) {
        var tenant=tenants.getRequiredRestaurantId();
        restaurants.findByIdForUpdate(tenant).orElseThrow(() ->
            new ResourceNotFoundException("RESTAURANT_NOT_FOUND","Restaurant not found"));
        var actor=users.getRequired();
        if(!actor.hasPermission(permission)) throw new AccessDeniedException("Required serving permission is missing");
        features.requireFeature(tenant,feature);
        var order=orders.findByIdAndRestaurantId(orderId,tenant).orElseThrow(() ->
            new ResourceNotFoundException("ORDER_NOT_FOUND","Order not found"));
        var line=items.findByIdAndRestaurantIdAndOrderId(itemId,tenant,orderId).orElseThrow(() ->
            new ResourceNotFoundException("ORDER_ITEM_NOT_FOUND","Order item not found"));
        policy.requireSubmitted(order);
        var lines=items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(tenant,orderId);
        if(!policy.requireTransition(line.getStatus(),source,target)) return mapper.detail(order,lines);
        lifecycle.requireVersion(order,expectedVersion);
        var before=audit.snapshot(order,lines);
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        line.setStatus(target); line.setUpdatedAt(now);
        order.setStatus(servingStatus.aggregate(order,lines));
        order.changed(now);
        items.save(line); orders.saveAndFlush(order);
        history.transitioned(order,line,actor.userId(),source,now);
        audit.record(actor,"ORDER_ITEM_"+target.name(),order,before,lines,ip);
        orders.flush();
        return mapper.detail(order,lines);
    }
}
