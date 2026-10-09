package com.possaas.modules.order.service.chain.update;
import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class UpdateOrderItemPrepareHandler implements OrderHandler<UpdateOrderItemContext,OrderResponse> {
    private final OrderMoneyCalculator money;
    private final OrderAudit audit;
    private final OrderResponseMapper mapper;
    @Override public OrderResponse handle(UpdateOrderItemContext c,OrderNext<UpdateOrderItemContext,OrderResponse> next) {
        var loaded=c.loaded(); var line=loaded.line(); var request=c.request();
        var quantity=request.quantity()==null?line.getQuantity():money.quantity(request.quantity());
        var note=request.note()==null?line.getNote():OrderRequestNormalizer.text(request.note());
        if(quantity.compareTo(line.getQuantity())==0 && Objects.equals(note,line.getNote()))
            return next.complete(mapper.detail(loaded.order(),loaded.lines()));
        var lineTotal=money.line(quantity,line.getUnitPrice());
        var totals=money.calculate(loaded.order(),loaded.lines(),l->l==line?lineTotal:l.getLineTotal());
        c.prepared(new UpdateOrderItemContext.Prepared(quantity,note,lineTotal,totals,audit.snapshot(loaded.order(),loaded.lines())));
        return next.proceed(c);
    }
}
