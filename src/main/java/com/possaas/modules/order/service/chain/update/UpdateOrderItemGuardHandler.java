package com.possaas.modules.order.service.chain.update;
import com.possaas.common.exception.*;
import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class UpdateOrderItemGuardHandler implements OrderHandler<UpdateOrderItemContext,OrderResponse> {
    private final OrderAccessService access;
    private final OrderSubmissionPolicy submission;
    private final OrderLifecyclePolicy lifecycle;
    private final OrderItemRepository items;
    @Override public OrderResponse handle(UpdateOrderItemContext c,OrderNext<UpdateOrderItemContext,OrderResponse> next) {
        var actor=c.facts().locked().actor(); submission.requireUpdate(actor);
        var order=access.order(actor.restaurantId(),c.orderId());
        lifecycle.requireEditable(order); lifecycle.requireVersion(order,c.request().expectedVersion());
        var lines=items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(actor.restaurantId(),order.getId());
        var line=lines.stream().filter(l->l.getId().equals(c.lineId())).findFirst()
            .orElseThrow(()->new ResourceNotFoundException("ORDER_ITEM_NOT_FOUND","Order line not found"));
        lifecycle.requirePending(line);
        if(c.request().quantity()==null && c.request().note()==null)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"EMPTY_UPDATE_REQUEST","No editable fields supplied");
        c.loaded(new UpdateOrderItemContext.Loaded(order,line,lines));
        return next.proceed(c);
    }
}
