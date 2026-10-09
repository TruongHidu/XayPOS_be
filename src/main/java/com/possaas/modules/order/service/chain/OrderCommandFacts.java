package com.possaas.modules.order.service.chain;
import com.possaas.modules.order.service.OrderAccessService;
import java.time.Instant;
import java.util.Objects;
public record OrderCommandFacts(OrderAccessService.Locked locked,Instant now,String ip) {
    public OrderCommandFacts { Objects.requireNonNull(locked); Objects.requireNonNull(now); }
    @Override public String toString() { return "OrderCommandFacts[private fields omitted]"; }
}
