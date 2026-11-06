package com.possaas.modules.order.service.chain;
import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.create.*;
import com.possaas.modules.order.service.chain.append.*;
import com.possaas.modules.order.service.chain.update.*;
import java.util.List;
import org.springframework.context.annotation.*;

@Configuration
public class OrderChainConfiguration {
    @Bean("createOrderChain")
    OrderChain<CreateOrderContext,OrderCreationService.Result> create(
            CreateOrderReplayHandler replay,CreateOrderPrepareHandler prepare,CreateOrderPersistHandler persist,OrderResponseMapper mapper) {
        return new OrderChain<>(List.of(replay,prepare,persist),c->{
            c.requirePersisted(); var p=c.prepared();
            return new OrderCreationService.Result(mapper.detail(p.order(),p.lines()),false);
        });
    }
    @Bean("addOrderItemsChain")
    OrderChain<AddOrderItemsContext,OrderAppendService.Result> append(
            AddOrderItemsReplayHandler replay,AddOrderItemsPrepareHandler prepare,AddOrderItemsPersistHandler persist,OrderResponseMapper mapper) {
        return new OrderChain<>(List.of(replay,prepare,persist),c->{
            c.requirePersisted(); return new OrderAppendService.Result(mapper.detail(c.order(),c.prepared().combined()),false);
        });
    }
    @Bean("updateOrderItemChain")
    OrderChain<UpdateOrderItemContext,OrderResponse> update(
            UpdateOrderItemGuardHandler guards,UpdateOrderItemPrepareHandler prepare,UpdateOrderItemPersistHandler persist,OrderResponseMapper mapper) {
        return new OrderChain<>(List.of(guards,prepare,persist),c->{
            c.requirePersisted(); return mapper.detail(c.loaded().order(),c.loaded().lines());
        });
    }
}
