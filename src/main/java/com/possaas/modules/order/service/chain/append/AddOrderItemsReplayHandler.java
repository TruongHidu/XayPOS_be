package com.possaas.modules.order.service.chain.append;
import com.possaas.modules.order.entity.OrderSubmissionMode;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class AddOrderItemsReplayHandler implements OrderHandler<AddOrderItemsContext,OrderAppendService.Result> {
    private final OrderAccessService access;
    private final OrderSubmissionPolicy submission;
    private final OrderRequestNormalizer normalizer;
    private final OrderIdempotencyService fingerprint;
    private final OrderAppendIdempotency idempotency;
    private final OrderItemRepository items;
    private final OrderResponseMapper mapper;
    @Override public OrderAppendService.Result handle(AddOrderItemsContext c,OrderNext<AddOrderItemsContext,OrderAppendService.Result> next) {
        var actor=c.facts().locked().actor(); submission.requireAppend(actor);
        var order=access.order(actor.restaurantId(),c.orderId()); c.order(order);
        var request=normalizer.normalize(c.request()); String key=null,hash=null;
        if(request.effectiveMode()==OrderSubmissionMode.SUBMIT) {
            access.requireCreationFeatures(actor.restaurantId(),order.getServiceType());
            key=fingerprint.key(c.rawKey()); hash=fingerprint.appendHash(actor.userId(),order.getId(),request);
            if(idempotency.replay(actor.restaurantId(),order.getId(),actor.userId(),key,hash))
                return next.complete(new OrderAppendService.Result(mapper.detail(order,
                    items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(actor.restaurantId(),order.getId())),true));
        }
        c.normalized(new AddOrderItemsContext.Normalized(request,key,hash));
        return next.proceed(c);
    }
}
