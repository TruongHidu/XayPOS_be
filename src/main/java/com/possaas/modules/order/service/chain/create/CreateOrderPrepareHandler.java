package com.possaas.modules.order.service.chain.create;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.order.service.strategy.OrderCreationContext;
import com.possaas.modules.order.service.strategy.OrderCreationPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class CreateOrderPrepareHandler implements OrderHandler<CreateOrderContext,OrderCreationService.Result> {
    private final OrderFactory factory;
    private final OrderSessionServingPolicy serving;
    private final OrderCodeGenerator codes;
    private final OrderLineFactory lineFactory;
    private final OrderMoneyCalculator money;
    private final OrderConfirmationOperation confirmation;
    @Override public OrderCreationService.Result handle(CreateOrderContext c,OrderNext<CreateOrderContext,OrderCreationService.Result> next) {
        var actor=c.facts().locked().actor(); var normalized=c.normalized(); var request=normalized.request(); var now=c.facts().now();
        var context=new OrderCreationContext(actor.restaurantId(),actor.userId(),c.facts().locked().restaurant().getCurrencyCode(),
            request.tableSessionId(),request.guestCount(),now,request.tableId());
        var plan=factory.prepare(request.serviceType(),context);
        if(plan.kind()==OrderCreationPlan.Kind.EXISTING_SESSION)
            serving.requireAvailable(actor.restaurantId(),request.serviceType(),plan.tableSessionId());
        var order=new Order(); order.initialize(actor.restaurantId(),now); order.setOrderCode(codes.generate());
        order.setServiceType(request.serviceType()); order.setSourceChannel(request.sourceChannel()); order.setTableSessionId(plan.tableSessionId());
        order.setGuestCount(plan.guestCount()); order.setCurrencyCode(context.currencyCode()); order.setCreatedBy(actor.userId());
        order.setCustomerName(request.customerName()); order.setCustomerPhone(request.customerPhone()); order.setNote(request.note());
        order.setIdempotencyKey(normalized.key()); order.setRequestHash(normalized.hash());
        var lines=lineFactory.create(order,request.items(),1,now);
        money.recalculate(order,lines); // Only this transient aggregate changes.
        if(request.effectiveMode()==OrderSubmissionMode.SUBMIT) confirmation.validate(order,lines,actor);
        c.prepared(new CreateOrderContext.Prepared(order,lines,plan));
        return next.proceed(c);
    }
}
