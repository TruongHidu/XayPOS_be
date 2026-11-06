package com.possaas.modules.order.service;

import com.possaas.modules.menu.application.port.OrderMenuQuery;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.entity.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OrderLineFactory {
    private final OrderMenuQuery menu;
    private final OrderMoneyCalculator money;
    public List<OrderItem> create(Order order,List<OrderRequests.Line> requested,int firstNumber,Instant now) {
        var snapshots=menu.requireSellable(order.getRestaurantId(),requested.stream().map(OrderRequests.Line::itemId).toList());
        var result=new ArrayList<OrderItem>(); int number=firstNumber;
        for(var request:requested) {
            var source=snapshots.get(request.itemId()); var line=new OrderItem();
            line.initialize(order.getRestaurantId(),now); line.setOrderId(order.getId()); line.setItemId(source.id());
            line.setItemName(source.name()); line.setUnit(source.unit()); line.setQuantity(money.quantity(request.quantity()));
            line.setUnitPrice(money.amount(source.salePrice())); line.setLineTotal(money.line(line.getQuantity(),line.getUnitPrice()));
            line.setNote(request.note()); line.setLineNumber(number++); result.add(line);
        }
        return result;
    }
}
