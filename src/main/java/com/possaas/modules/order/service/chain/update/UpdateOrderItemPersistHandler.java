package com.possaas.modules.order.service.chain.update;
import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class UpdateOrderItemPersistHandler implements OrderHandler<UpdateOrderItemContext,OrderResponse> {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderMoneyCalculator money;
    private final OrderAudit audit;
    @Override public OrderResponse handle(UpdateOrderItemContext c,OrderNext<UpdateOrderItemContext,OrderResponse> next) {
        var actor=c.facts().locked().actor(); var now=c.facts().now(); var l=c.loaded(); var p=c.prepared();
        l.line().setQuantity(p.quantity()); l.line().setNote(p.note()); l.line().setLineTotal(p.lineTotal()); l.line().setUpdatedAt(now);
        money.apply(l.order(),p.totals()); l.order().changed(now);
        items.saveAll(l.lines()); orders.saveAndFlush(l.order());
        audit.record(actor,"ORDER_ITEM_UPDATED",l.order(),p.before(),l.lines(),c.facts().ip()); orders.flush();
        c.persisted(); return next.proceed(c);
    }
}
