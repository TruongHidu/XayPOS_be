package com.possaas.modules.order.service.chain.append;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.service.OrderMoneyCalculator;
import com.possaas.modules.order.service.chain.OrderCommandFacts;
import java.util.*;
public final class AddOrderItemsContext {
    private final OrderCommandFacts facts;
    private final UUID orderId;
    private final OrderRequests.AddItems request;
    private final String rawKey;
    private Order order;
    private Normalized normalized;
    private Prepared prepared;
    private boolean persisted;
    public AddOrderItemsContext(OrderCommandFacts facts,UUID id,OrderRequests.AddItems request,String key) {
        this.facts=facts; this.orderId=id; this.request=request; this.rawKey=key;
    }
    public OrderCommandFacts facts() { return facts; }
    public UUID orderId() { return orderId; }
    public OrderRequests.AddItems request() { return request; }
    public String rawKey() { return rawKey; }
    public Order order() { return Objects.requireNonNull(order,"Append root stage missing"); }
    public void order(Order value) { order=value; }
    public Normalized normalized() { return Objects.requireNonNull(normalized,"Append normalization stage missing"); }
    public void normalized(Normalized value) { normalized=value; }
    public Prepared prepared() { return Objects.requireNonNull(prepared,"Append preparation stage missing"); }
    public void prepared(Prepared value) { prepared=value; }
    public void persisted() { persisted=true; }
    public void requirePersisted() { if(!persisted) throw new IllegalStateException("Append persistence stage missing"); }
    public record Normalized(OrderRequests.AddItems request,String key,String hash) {
        @Override public String toString() { return "NormalizedAppend[private fields omitted]"; }
    }
    public record Prepared(List<OrderItem> added,List<OrderItem> combined,OrderMoneyCalculator.Totals totals,Map<String,Object> before) {
        public Prepared { added=List.copyOf(added); combined=List.copyOf(combined); }
        @Override public String toString() { return "PreparedAppend[private fields omitted]"; }
    }
    @Override public String toString() { return "AddOrderItemsContext[private fields omitted]"; }
}
