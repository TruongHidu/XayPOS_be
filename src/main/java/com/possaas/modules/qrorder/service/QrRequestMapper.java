package com.possaas.modules.qrorder.service;

import com.possaas.modules.qrorder.dto.QrPublicResponse;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.entity.OrderItem;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class QrRequestMapper {
    private final Clock clock;
    private final OrderItemRepository orderItems;
    public QrPublicResponse publicResponse(QrOrderRequest request,List<QrOrderRequestItem> items) {
        Set<UUID> linked=items.stream().map(QrOrderRequestItem::getApprovedOrderItemId).filter(Objects::nonNull).collect(Collectors.toSet());
        // One bounded batch, filtered by both tenant and exact request line linkage.
        Map<UUID,OrderItem> approved=linked.isEmpty()?Map.of():orderItems.findAllById(linked).stream()
                .filter(i->i.getRestaurantId().equals(request.getRestaurantId()) && i.getOrderId().equals(request.getApprovedOrderId()))
                .collect(Collectors.toMap(OrderItem::getId,Function.identity()));
        var lines=items.stream().map(i->{var line=i.getApprovedOrderItemId()==null?null:approved.get(i.getApprovedOrderItemId());
            return new QrPublicResponse.Line(i.getId(),i.getItemId(),i.getQuotedItemName(),i.getQuotedUnit(),i.getQuantity(),
                    i.getQuotedUnitPrice(),i.getQuotedLineTotal(),i.getNote(),i.getApprovedOrderItemId(),
                    line==null?null:line.getUnitPrice(),line==null?null:line.getLineTotal(),line==null?null:line.getStatus());}).toList();
        return new QrPublicResponse(request.getId(),request.getEntryType(),request.getEntryType().serviceType(),
                request.getEntryType().sourceChannel(),request.getStatus(),request.getVersion(),!request.getExpiresAt().isAfter(clock.instant()),
                request.getCurrencyCode(),request.getEstimatedAmount(),request.getApprovedAmount(),request.getApprovedOrderCode(),
                request.getCreatedAt(),request.getExpiresAt(),lines);
    }
}
