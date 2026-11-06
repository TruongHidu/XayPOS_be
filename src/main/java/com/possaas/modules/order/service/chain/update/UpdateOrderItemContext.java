package com.possaas.modules.order.service.chain.update;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.service.OrderMoneyCalculator;
import com.possaas.modules.order.service.chain.OrderCommandFacts;
import java.math.BigDecimal;
import java.util.*;
public final class UpdateOrderItemContext {
    private final OrderCommandFacts facts;
    private final UUID orderId,lineId;
    private final OrderRequests.UpdateItem request;
    private Loaded loaded;
    private Prepared prepared;
    private boolean persisted;
    public UpdateOrderItemContext(OrderCommandFacts facts,UUID id,UUID lineId,OrderRequests.UpdateItem request) {
        this.facts=facts; this.orderId=id; this.lineId=lineId; this.request=request;
    }
    public OrderCommandFacts facts() { return facts; }
    public UUID orderId() { return orderId; }
    public UUID lineId() { return lineId; }
    public OrderRequests.UpdateItem request() { return request; }
    public Loaded loaded() { return Objects.requireNonNull(loaded,"Update guards stage missing"); }
    public void loaded(Loaded value) { loaded=value; }
    public Prepared prepared() { return Objects.requireNonNull(prepared,"Update preparation stage missing"); }
    public void prepared(Prepared value) { prepared=value; }
    public void persisted() { persisted=true; }
    public void requirePersisted() { if(!persisted) throw new IllegalStateException("Update persistence stage missing"); }
    public record Loaded(Order order,OrderItem line,List<OrderItem> lines) {
        public Loaded { lines=List.copyOf(lines); }
        @Override public String toString() { return "LoadedUpdate[private fields omitted]"; }
    }
    public record Prepared(BigDecimal quantity,String note,BigDecimal lineTotal,OrderMoneyCalculator.Totals totals,Map<String,Object> before) {
        @Override public String toString() { return "PreparedUpdate[private fields omitted]"; }
    }
    @Override public String toString() { return "UpdateOrderItemContext[private fields omitted]"; }
}
