package com.possaas.modules.order.service;
import com.possaas.modules.order.dto.*;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.order.service.chain.update.UpdateOrderItemContext;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderItemUpdateService {
    private final OrderAccessService access;
    private final Clock clock;
    private final OrderChain<UpdateOrderItemContext,OrderResponse> chain;
    public OrderItemUpdateService(OrderAccessService access,Clock clock,
            @Qualifier("updateOrderItemChain") OrderChain<UpdateOrderItemContext,OrderResponse> chain) {
        this.access=access; this.clock=clock; this.chain=chain;
    }
    @Transactional
    public OrderResponse update(UUID id,UUID lineId,OrderRequests.UpdateItem request,String ip) {
        var locked=access.lock();
        var facts=new OrderCommandFacts(locked,clock.instant().truncatedTo(ChronoUnit.MICROS),ip);
        return chain.execute(new UpdateOrderItemContext(facts,id,lineId,request));
    }
}
