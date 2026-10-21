package com.possaas.modules.order.service.chain.append;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class AddOrderItemsPersistHandler implements OrderHandler<AddOrderItemsContext,OrderAppendService.Result> {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderMoneyCalculator money;
    private final OrderHistory history;
    private final OrderAudit audit;
    private final OrderConfirmationOperation confirmation;
    private final OrderAppendIdempotency idempotency;
    private final OrderServingStatusPolicy servingStatus;
    private final OrderInventoryAllocationOperation inventory;
    @Override public OrderAppendService.Result handle(AddOrderItemsContext c,OrderNext<AddOrderItemsContext,OrderAppendService.Result> next) {
        var actor=c.facts().locked().actor(); var now=c.facts().now(); var order=c.order(); var p=c.prepared();
        items.saveAll(p.added());
        if(c.normalized().request().effectiveMode()==OrderSubmissionMode.SUBMIT && order.getStatus()!=OrderStatus.OPEN) {
            inventory.validate(order,p.combined());
            inventory.accept(order,p.added(),actor,now,c.facts().ip());
            p.added().forEach(line -> line.setSentToKitchenAt(now));
        }
        money.apply(order,p.totals());
        if(c.normalized().request().effectiveMode()==OrderSubmissionMode.SUBMIT)
            order.setStatus(servingStatus.aggregate(order,p.combined()));
        order.changed(now); orders.saveAndFlush(order);
        history.created(order,p.added(),actor.userId(),now);
        audit.record(actor,"ORDER_ITEMS_ADDED",order,p.before(),p.combined(),c.facts().ip());
        if(c.normalized().request().effectiveMode()==OrderSubmissionMode.SUBMIT) {
            if(order.getStatus()==OrderStatus.OPEN) confirmation.confirm(order,p.combined(),actor,now,c.facts().ip());
            idempotency.record(actor.restaurantId(),order.getId(),actor.userId(),c.normalized().key(),c.normalized().hash(),now);
        }
        orders.flush(); c.persisted();
        return next.proceed(c);
    }
}
