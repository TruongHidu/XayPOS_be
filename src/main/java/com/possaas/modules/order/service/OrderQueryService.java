package com.possaas.modules.order.service;

import com.possaas.modules.order.dto.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.table.application.port.OrderSessionQuery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class OrderQueryService {
    private final OrderAccessService access;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderResponseMapper mapper;
    private final OrderSessionServingPolicy serving;
    private final OrderSessionQuery sessions;
    public Optional<OrderResponse> activeOrder(UUID sessionId) {
        var actor=access.read();
        if(!actor.hasPermission("ORDER_VIEW")) throw new AccessDeniedException("Required order permission is missing");
        sessions.requireOwned(actor.restaurantId(),sessionId);
        return serving.findServing(actor.restaurantId(),sessionId).map(order ->
            mapper.detail(order,items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(actor.restaurantId(),order.getId())));
    }
    public PageResponse<OrderSummaryResponse> search(OrderSearch search) {
        var actor=access.read(); var page=orders.findAll(OrderSpecifications.search(actor.restaurantId(),search),search.pageable());
        return PageResponse.from(page.map(mapper::summary));
    }
    public OrderResponse detail(java.util.UUID id) {
        var actor=access.read(); var order=access.order(actor.restaurantId(),id);
        return mapper.detail(order,items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(actor.restaurantId(),id));
    }
}
