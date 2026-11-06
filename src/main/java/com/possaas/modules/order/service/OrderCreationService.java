package com.possaas.modules.order.service;
import com.possaas.modules.order.dto.*;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.order.service.chain.create.CreateOrderContext;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCreationService {
    private final OrderAccessService access;
    private final Clock clock;
    private final OrderChain<CreateOrderContext,Result> chain;
    public OrderCreationService(OrderAccessService access,Clock clock,
            @Qualifier("createOrderChain") OrderChain<CreateOrderContext,Result> chain) {
        this.access=access; this.clock=clock; this.chain=chain;
    }
    @Transactional
    public Result create(OrderRequests.Create request,String key,String ip) {
        var locked=access.lock();
        var facts=new OrderCommandFacts(locked,clock.instant().truncatedTo(ChronoUnit.MICROS),ip);
        return chain.execute(new CreateOrderContext(facts,request,key));
    }
    public record Result(OrderResponse response,boolean replay) {}
}
