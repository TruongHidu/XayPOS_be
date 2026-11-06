package com.possaas.modules.order.service;
import com.possaas.modules.order.dto.*;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.order.service.chain.append.AddOrderItemsContext;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderAppendService {
    private final OrderAccessService access;
    private final Clock clock;
    private final OrderChain<AddOrderItemsContext,Result> chain;
    public OrderAppendService(OrderAccessService access,Clock clock,
            @Qualifier("addOrderItemsChain") OrderChain<AddOrderItemsContext,Result> chain) {
        this.access=access; this.clock=clock; this.chain=chain;
    }
    @Transactional
    public Result addItems(UUID id,OrderRequests.AddItems request,String key,String ip) {
        var locked=access.lock();
        var facts=new OrderCommandFacts(locked,clock.instant().truncatedTo(ChronoUnit.MICROS),ip);
        return chain.execute(new AddOrderItemsContext(facts,id,request,key));
    }
    public record Result(OrderResponse response,boolean replay) {}
}
