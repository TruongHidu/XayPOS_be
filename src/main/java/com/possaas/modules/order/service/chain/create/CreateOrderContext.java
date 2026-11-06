package com.possaas.modules.order.service.chain.create;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.service.chain.OrderCommandFacts;
import com.possaas.modules.order.service.strategy.OrderCreationPlan;
import java.util.*;
public final class CreateOrderContext {
    private final OrderCommandFacts facts;
    private final OrderRequests.Create request;
    private final String rawKey;
    private Normalized normalized;
    private Prepared prepared;
    private boolean persisted;
    public CreateOrderContext(OrderCommandFacts facts,OrderRequests.Create request,String key) {
        this.facts=facts; this.request=request; this.rawKey=key;
    }
    public OrderCommandFacts facts() { return facts; }
    public OrderRequests.Create request() { return request; }
    public String rawKey() { return rawKey; }
    public Normalized normalized() { return Objects.requireNonNull(normalized,"Create normalization stage missing"); }
    public void normalized(Normalized value) { normalized=value; }
    public Prepared prepared() { return Objects.requireNonNull(prepared,"Create preparation stage missing"); }
    public void prepared(Prepared value) { prepared=value; }
    public void persisted() { persisted=true; }
    public void requirePersisted() { if(!persisted) throw new IllegalStateException("Create persistence stage missing"); }
    public record Normalized(OrderRequests.Create request,String key,String hash) {
        @Override public String toString() { return "NormalizedCreate[private fields omitted]"; }
    }
    public record Prepared(Order order,List<OrderItem> lines,OrderCreationPlan seating) {
        public Prepared { Objects.requireNonNull(order); lines=List.copyOf(lines); Objects.requireNonNull(seating); }
        @Override public String toString() { return "PreparedCreate[private fields omitted]"; }
    }
    @Override public String toString() { return "CreateOrderContext[private fields omitted]"; }
}
