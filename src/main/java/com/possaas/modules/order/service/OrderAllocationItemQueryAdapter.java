package com.possaas.modules.order.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.inventory.application.port.OrderAllocationItemQuery;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.repository.OrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor @Transactional(readOnly = true)
public class OrderAllocationItemQueryAdapter implements OrderAllocationItemQuery {
    private final OrderRepository orders;
    private final OrderItemRepository items;

    @Override public Item find(UUID tenant, UUID orderId, UUID orderItemId) {
        orders.findByIdAndRestaurantId(orderId, tenant).orElseThrow(() ->
            new ResourceNotFoundException("ORDER_NOT_FOUND", "Order not found"));
        var line = items.findByIdAndRestaurantIdAndOrderId(orderItemId, tenant, orderId).orElseThrow(() ->
            new ResourceNotFoundException("ORDER_ITEM_NOT_FOUND", "Order item not found"));
        return new Item(orderId, line.getId(), line.getStatus().name(), line.getSentToKitchenAt() != null,
                line.getQuantity(), line.getUnit());
    }
}
