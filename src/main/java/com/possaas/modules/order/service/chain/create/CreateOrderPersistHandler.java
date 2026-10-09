package com.possaas.modules.order.service.chain.create;
import com.possaas.modules.order.entity.OrderSubmissionMode;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.order.service.strategy.OrderCreationPlan;
import com.possaas.modules.table.application.port.OrderSeatingCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class CreateOrderPersistHandler implements OrderHandler<CreateOrderContext,OrderCreationService.Result> {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderHistory history;
    private final OrderAudit audit;
    private final OrderConfirmationOperation confirmation;
    private final OrderSeatingCommand seating;
    @Override public OrderCreationService.Result handle(CreateOrderContext c,OrderNext<CreateOrderContext,OrderCreationService.Result> next) {
        var actor=c.facts().locked().actor(); var now=c.facts().now(); var p=c.prepared();
        if(p.seating().kind()==OrderCreationPlan.Kind.NEW_SESSION)
            p.order().setTableSessionId(seating.open(actor.restaurantId(),p.seating().tableId(),
                p.seating().guestCount(),now,c.facts().ip()));
        orders.save(p.order()); p.lines().forEach(line -> line.setOrderId(p.order().getId())); items.saveAllAndFlush(p.lines());
        history.created(p.order(),p.lines(),actor.userId(),now);
        audit.record(actor,"ORDER_CREATED",p.order(),null,p.lines(),c.facts().ip()); orders.flush();
        if(c.normalized().request().effectiveMode()==OrderSubmissionMode.SUBMIT)
            confirmation.confirm(p.order(),p.lines(),actor,now,c.facts().ip());
        orders.flush(); c.persisted();
        return next.proceed(c);
    }
}
