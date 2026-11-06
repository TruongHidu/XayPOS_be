package com.possaas.modules.order.service.chain.append;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class AddOrderItemsPrepareHandler implements OrderHandler<AddOrderItemsContext,OrderAppendService.Result> {
    private final OrderAppendPolicy append;
    private final OrderLifecyclePolicy lifecycle;
    private final OrderItemRepository items;
    private final OrderLineFactory lineFactory;
    private final OrderMoneyCalculator money;
    private final OrderAudit audit;
    private final OrderConfirmationOperation confirmation;
    @Override public OrderAppendService.Result handle(AddOrderItemsContext c,OrderNext<AddOrderItemsContext,OrderAppendService.Result> next) {
        var actor=c.facts().locked().actor(); var order=c.order(); var request=c.normalized().request();
        append.requireAppendable(order,request.effectiveMode(),c.facts().locked().restaurant().getCurrencyCode());
        lifecycle.requireVersion(order,request.expectedVersion());
        var lines=items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(actor.restaurantId(),order.getId());
        var before=audit.snapshot(order,lines);
        int first=lines.stream().mapToInt(OrderItem::getLineNumber).max().orElse(0)+1;
        var added=lineFactory.create(order,request.items(),first,c.facts().now());
        var combined=new ArrayList<>(lines); combined.addAll(added);
        var totals=money.calculate(order,combined); // No managed root or old line is dirtied.
        if(request.effectiveMode()==OrderSubmissionMode.SUBMIT && order.getStatus()==OrderStatus.OPEN)
            confirmation.validate(order,combined,actor);
        c.prepared(new AddOrderItemsContext.Prepared(added,combined,totals,before));
        return next.proceed(c);
    }
}
