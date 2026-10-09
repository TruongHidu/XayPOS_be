package com.possaas.modules.order.service;

import com.possaas.common.exception.*;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.dto.*;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class OrderCommandService {
    private final OrderAccessService access;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderAppendService append;
    private final OrderItemUpdateService itemUpdates;
    private final OrderConfirmationOperation confirmation;
    private final OrderLifecyclePolicy lifecycle;
    private final OrderMoneyCalculator money;
    private final OrderHistory history;
    private final OrderAudit audit;
    private final OrderResponseMapper mapper;
    private final Clock clock;

    public OrderResponse update(UUID id,OrderRequests.Update request,String ip) {
        var locked=access.lock(); var actor=locked.actor(); var order=access.order(actor.restaurantId(),id);
        lifecycle.requireEditable(order); lifecycle.requireVersion(order,request.expectedVersion());
        if(request.guestCount()==null && request.note()==null && request.customerName()==null && request.customerPhone()==null) throw empty();
        var lines=lines(order); var before=audit.snapshot(order,lines); boolean changed=false;
        if(request.guestCount()!=null && order.getGuestCount()!=request.guestCount().shortValue()) { order.setGuestCount(request.guestCount().shortValue()); changed=true; }
        if(request.note()!=null && !Objects.equals(order.getNote(),OrderRequestNormalizer.text(request.note()))) { order.setNote(OrderRequestNormalizer.text(request.note())); changed=true; }
        if(request.customerName()!=null && !Objects.equals(order.getCustomerName(),OrderRequestNormalizer.text(request.customerName()))) { order.setCustomerName(OrderRequestNormalizer.text(request.customerName())); changed=true; }
        if(request.customerPhone()!=null && !Objects.equals(order.getCustomerPhone(),OrderRequestNormalizer.text(request.customerPhone()))) { order.setCustomerPhone(OrderRequestNormalizer.text(request.customerPhone())); changed=true; }
        if(changed) finish(actor,order,lines,before,"ORDER_UPDATED",now(),ip);
        return mapper.detail(order,lines);
    }

    public OrderResponse addItems(UUID id,OrderRequests.AddItems request,String ip) {
        return append.addItems(id,request,null,ip).response();
    }

    public OrderResponse updateItem(UUID id,UUID lineId,OrderRequests.UpdateItem request,String ip) {
        return itemUpdates.update(id,lineId,request,ip);
    }

    public OrderResponse confirm(UUID id,OrderRequests.Version request,String ip) {
        var locked=access.lock(); var actor=locked.actor(); var order=access.order(actor.restaurantId(),id); var lines=lines(order);
        if(order.getStatus()==OrderStatus.CONFIRMED) return mapper.detail(order,lines);
        lifecycle.requireEditable(order); lifecycle.requireVersion(order,request.expectedVersion());
        confirmation.confirm(order,lines,actor,now(),ip); return mapper.detail(order,lines);
    }

    public OrderResponse cancelItem(UUID id,UUID lineId,OrderRequests.Cancel request,String ip) {
        var locked=access.lock(); var actor=locked.actor(); var order=access.order(actor.restaurantId(),id);
        var lines=lines(order); var line=line(lines,lineId); lifecycle.requireLineCancellationPermission(order,actor);
        if(line.getStatus()==OrderItemStatus.CANCELLED) return mapper.detail(order,lines);
        lifecycle.requireCancellationState(order); lifecycle.requireVersion(order,request.expectedVersion()); lifecycle.requirePending(line);
        var before=audit.snapshot(order,lines); var now=now(); String reason=request.reason().trim();
        cancelLine(line,reason,now); boolean cancelledOrder=lines.stream().allMatch(l->l.getStatus()==OrderItemStatus.CANCELLED);
        if(cancelledOrder) cancelRoot(order,reason,now);
        finish(actor,order,lines,before,"ORDER_ITEM_CANCELLED",now,ip); history.cancelled(order,List.of(line),actor.userId(),reason,now);
        if(cancelledOrder) audit.record(actor,"ORDER_CANCELLED",order,before,lines,ip);
        orders.flush(); return mapper.detail(order,lines);
    }

    public OrderResponse cancel(UUID id,OrderRequests.Cancel request,String ip) {
        var locked=access.lock(); var actor=locked.actor(); var order=access.order(actor.restaurantId(),id); var lines=lines(order);
        if(order.getStatus()==OrderStatus.CANCELLED) return mapper.detail(order,lines);
        lifecycle.requireCancellable(order,lines); lifecycle.requireVersion(order,request.expectedVersion());
        var before=audit.snapshot(order,lines); var now=now(); String reason=request.reason().trim();
        var changed=lines.stream().filter(l->l.getStatus()!=OrderItemStatus.CANCELLED).toList(); changed.forEach(l->cancelLine(l,reason,now));
        cancelRoot(order,reason,now); finish(actor,order,lines,before,"ORDER_CANCELLED",now,ip);
        history.cancelled(order,changed,actor.userId(),reason,now); orders.flush(); return mapper.detail(order,lines);
    }

    private void finish(CurrentUser actor,Order order,List<OrderItem> lines,Map<String,Object> before,String action,Instant now,String ip) {
        money.recalculate(order,lines); order.changed(now); items.saveAll(lines); orders.saveAndFlush(order);
        audit.record(actor,action,order,before,lines,ip); orders.flush();
    }
    private List<OrderItem> lines(Order order) { return new ArrayList<>(items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(order.getRestaurantId(),order.getId())); }
    private OrderItem line(List<OrderItem> lines,UUID id) { return lines.stream().filter(l->l.getId().equals(id)).findFirst().orElseThrow(()->new ResourceNotFoundException("ORDER_ITEM_NOT_FOUND","Order line not found")); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private void cancelLine(OrderItem line,String reason,Instant now) { line.setStatus(OrderItemStatus.CANCELLED); line.setCancelReason(reason); line.setUpdatedAt(now); }
    private void cancelRoot(Order order,String reason,Instant now) { order.setStatus(OrderStatus.CANCELLED); order.setCancelledAt(now); order.setCancelReason(reason); }
    private BusinessException empty() { return new BusinessException(HttpStatus.BAD_REQUEST,"EMPTY_UPDATE_REQUEST","No editable fields supplied"); }
}
