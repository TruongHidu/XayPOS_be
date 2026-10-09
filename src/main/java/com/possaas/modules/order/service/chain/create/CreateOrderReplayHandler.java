package com.possaas.modules.order.service.chain.create;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class CreateOrderReplayHandler implements OrderHandler<CreateOrderContext,OrderCreationService.Result> {
    private final OrderAccessService access;
    private final OrderRequestNormalizer normalizer;
    private final OrderSubmissionPolicy submission;
    private final OrderIdempotencyService idempotency;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderResponseMapper mapper;
    @Override public OrderCreationService.Result handle(CreateOrderContext c,OrderNext<CreateOrderContext,OrderCreationService.Result> next) {
        var actor=c.facts().locked().actor();
        var request=normalizer.normalize(c.request());
        access.requireCreationFeatures(actor.restaurantId(),request.serviceType());
        submission.requireCreate(actor,request.effectiveMode());
        var key=idempotency.key(c.rawKey()); var hash=idempotency.hash(actor.userId(),request);
        c.normalized(new CreateOrderContext.Normalized(request,key,hash));
        var existing=orders.findByRestaurantIdAndIdempotencyKey(actor.restaurantId(),key);
        if(existing.isPresent()) {
            var order=existing.get(); idempotency.requireReplayMatch(order,actor.userId(),hash);
            return next.complete(new OrderCreationService.Result(mapper.detail(order,
                items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(actor.restaurantId(),order.getId())),true));
        }
        return next.proceed(c);
    }
}
