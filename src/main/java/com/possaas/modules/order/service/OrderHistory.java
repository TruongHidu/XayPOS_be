package com.possaas.modules.order.service;

import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.OrderItemStatusHistoryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OrderHistory {
    private final OrderItemStatusHistoryRepository histories;
    public void created(Order order,List<OrderItem> lines,UUID actor,Instant now) { record(order,lines,actor,null,OrderItemStatus.PENDING,null,now); }
    public void cancelled(Order order,List<OrderItem> lines,UUID actor,String reason,Instant now) { record(order,lines,actor,OrderItemStatus.PENDING,OrderItemStatus.CANCELLED,reason,now); }
    private void record(Order order,List<OrderItem> lines,UUID actor,OrderItemStatus from,OrderItemStatus to,String note,Instant now) {
        histories.saveAll(lines.stream().map(line->{
            var entry=new OrderItemStatusHistory(); entry.setRestaurantId(order.getRestaurantId()); entry.setOrderItemId(line.getId());
            entry.setFromStatus(from); entry.setToStatus(to); entry.setChangedBy(actor); entry.setNote(note);
            entry.setChangedAt(now.truncatedTo(ChronoUnit.MICROS)); entry.setChangeSequence(order.getMutationSequence()); return entry;
        }).toList());
    }
}
