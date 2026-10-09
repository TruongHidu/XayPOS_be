package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.dto.*;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.order.service.chain.create.*;
import com.possaas.modules.order.service.chain.append.*;
import com.possaas.modules.order.service.chain.update.*;
import com.possaas.modules.order.service.strategy.OrderCreationPlan;
import com.possaas.modules.table.application.port.OrderSeatingCommand;
import com.possaas.modules.restaurant.entity.Restaurant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class OrderChainTest {
    @Test void explicitOrderReplayAndNoOpDoNotReachDownstreamOrTerminal() {
        var trace=new ArrayList<String>();
        OrderHandler<String,String> first=(context,next)->{trace.add("first"); return next.proceed(context);};
        OrderHandler<String,String> replay=(context,next)->{trace.add("replay"); return next.complete("current resource");};
        OrderHandler<String,String> writer=(context,next)->{throw new AssertionError("Replay reached writer");};
        var chain=new OrderChain<>(List.of(first,replay,writer),context->{throw new AssertionError("Replay reached terminal");});
        assertThat(chain.execute("request")).isEqualTo("current resource");
        assertThat(trace).containsExactly("first","replay");
        var noOp=new OrderChain<String,String>(List.of((context,next)->next.complete("no-op")),context->{throw new AssertionError();});
        assertThat(noOp.execute("request")).isEqualTo("no-op");
    }
    @Test void exceptionStopsDownstreamAndContinuationCannotBeUsedTwice() {
        var failure=new IllegalArgumentException("rule failed");
        var failed=new OrderChain<String,String>(List.of((context,next)->{throw failure;},
            (context,next)->{throw new AssertionError();}),context->{throw new AssertionError();});
        assertThatThrownBy(()->failed.execute("request")).isSameAs(failure);
        var doubled=new OrderChain<String,String>(List.of((context,next)->{
            next.proceed(context); return next.proceed(context);
        }),context->context);
        assertThatThrownBy(()->doubled.execute("request")).isInstanceOf(IllegalStateException.class).hasMessageContaining("once");
    }
    @Test void implicitSkipForeignResultNullAndMissingTerminalAreRejected() {
        var skip=new OrderChain<String,String>(List.of((context,next)->"fake success"),context->context);
        assertThatThrownBy(()->skip.execute("request")).isInstanceOf(IllegalStateException.class);
        var foreign=new OrderChain<String,String>(List.of((context,next)->{next.proceed(context); return "unrelated";}),context->context);
        assertThatThrownBy(()->foreign.execute("request")).isInstanceOf(IllegalStateException.class);
        var emptyTerminal=new OrderChain<String,String>(List.of(),context->null);
        assertThatThrownBy(()->emptyTerminal.execute("request")).isInstanceOf(NullPointerException.class);
        var nullEarly=new OrderChain<String,String>(List.of((context,next)->next.complete(null)),context->context);
        assertThatThrownBy(()->nullEarly.execute("request")).isInstanceOf(NullPointerException.class);
    }
    @Test void sharedSingletonChainUsesIndependentInvocationState() throws Exception {
        var barrier=new CyclicBarrier(2);
        OrderHandler<List<String>,List<String>> handler=(context,next)->{
            try { barrier.await(5,TimeUnit.SECONDS); } catch(Exception e) { throw new IllegalStateException(e); }
            context.add("checked"); return next.proceed(context);
        };
        var chain=new OrderChain<>(List.of(handler),context->{context.add("terminal"); return context;});
        var a=new ArrayList<>(List.of("actor-a")); var b=new ArrayList<>(List.of("actor-b"));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->chain.execute(a)); var two=pool.submit(()->chain.execute(b));
            assertThat(one.get(10,TimeUnit.SECONDS)).containsExactly("actor-a","checked","terminal");
            assertThat(two.get(10,TimeUnit.SECONDS)).containsExactly("actor-b","checked","terminal");
        } finally { pool.shutdownNow(); }
    }
    @Test void contextsAreActionTypedAndDoNotExposePrivateRequestState() {
        var facts=facts();
        assertThat(new CreateOrderContext(facts,new OrderRequests.Create(ServiceType.TAKEAWAY,SourceChannel.WAITER,null,null,null,null,null,List.of()),"private-key").toString())
            .doesNotContain("private-key");
        assertThat(new AddOrderItemsContext(facts,UUID.randomUUID(),new OrderRequests.AddItems(0L,List.of()),"private-key").toString()).doesNotContain("private-key");
        assertThat(new UpdateOrderItemContext(facts,UUID.randomUUID(),UUID.randomUUID(),new OrderRequests.UpdateItem(0L,null,"private-note")).toString())
            .doesNotContain("private-note");
        assertThat(facts.toString()).doesNotContain("private-email");
    }
    @Test void appendPreparationValidationFailureNeverDirtiesManagedAggregate() {
        var order=new Order(); order.setId(UUID.randomUUID()); order.initialize(UUID.randomUUID(),Instant.EPOCH);
        order.setCurrencyCode("VND"); order.setServiceType(ServiceType.TAKEAWAY);
        order.setTotalAmount(new BigDecimal("10.00")); order.setSubtotalAmount(new BigDecimal("10.00"));
        var old=line(order,BigDecimal.ONE,new BigDecimal("10.00"));
        var added=line(order,BigDecimal.ONE,new BigDecimal("20.00")); added.setId(null);
        var repository=mock(OrderItemRepository.class); when(repository.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(any(),any())).thenReturn(List.of(old));
        var factory=mock(OrderLineFactory.class); when(factory.create(any(),any(),anyInt(),any())).thenReturn(List.of(added));
        var confirmation=mock(OrderConfirmationOperation.class);
        doThrow(new IllegalArgumentException("old item not sellable")).when(confirmation).validate(any(),any(),any());
        var c=new AddOrderItemsContext(facts(),order.getId(),new OrderRequests.AddItems(0L,List.of(),OrderSubmissionMode.SUBMIT),"key");
        c.order(order); c.normalized(new AddOrderItemsContext.Normalized(c.request(),"key","hash"));
        var handler=new AddOrderItemsPrepareHandler(mock(OrderAppendPolicy.class),new OrderLifecyclePolicy(),repository,
            factory,new OrderMoneyCalculator(),new OrderAudit(mock(com.possaas.modules.audit.service.AuditService.class)),confirmation);
        var chain=new OrderChain<AddOrderItemsContext,OrderAppendService.Result>(List.of(handler),context->{throw new AssertionError("Invalid prep reached persistence");});
        assertThatThrownBy(()->chain.execute(c)).hasMessageContaining("not sellable");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("10.00"); assertThat(order.getMutationSequence()).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.OPEN); assertThat(old.getQuantity()).isEqualByComparingTo("1");
    }
    @Test void updatePreparationCalculatesPatchWithoutChangingOldLineAndRejectsOverflow() {
        var order=new Order(); order.initialize(UUID.randomUUID(),Instant.EPOCH); order.setCurrencyCode("VND");
        order.setTotalAmount(new BigDecimal("10")); order.setSubtotalAmount(new BigDecimal("10"));
        var old=line(order,BigDecimal.ONE,new BigDecimal("10.00"));
        var c=new UpdateOrderItemContext(facts(),order.getId(),old.getId(),new OrderRequests.UpdateItem(0L,new BigDecimal("3"),"new note"));
        c.loaded(new UpdateOrderItemContext.Loaded(order,old,List.of(old)));
        var handler=new UpdateOrderItemPrepareHandler(new OrderMoneyCalculator(),new OrderAudit(mock(com.possaas.modules.audit.service.AuditService.class)),new OrderResponseMapper());
        var chain=new OrderChain<UpdateOrderItemContext,OrderResponse>(List.of(handler),context->new OrderResponseMapper().detail(order,List.of(old)));
        chain.execute(c);
        assertThat(c.prepared().lineTotal()).isEqualByComparingTo("30");
        assertThat(old.getQuantity()).isEqualByComparingTo("1"); assertThat(old.getNote()).isNull();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("10"); assertThat(order.getMutationSequence()).isZero();
        old.setUnitPrice(new BigDecimal("999999999999.99"));
        assertThatThrownBy(()->chain.execute(c)).isInstanceOf(BusinessException.class);
        assertThat(old.getQuantity()).isEqualByComparingTo("1");
    }
    @Test void servingPolicyDetectsInconsistentReadInsteadOfChoosingFirst() {
        var repo=mock(OrderRepository.class); var tenant=UUID.randomUUID(); var session=UUID.randomUUID();
        when(repo.findAllByRestaurantIdAndTableSessionIdAndServiceTypeAndStatusIn(any(),any(),any(),any()))
            .thenReturn(List.of(new Order(),new Order()));
        assertThatThrownBy(()->new OrderSessionServingPolicy(repo).findServing(tenant,session))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("INCONSISTENT_SERVING_ORDER_STATE"));
    }
    @Test void createPreparationCarriesNewSessionPlanButNeverChecksNullServingSlotOrWrites() {
        var facts=facts(); var target=UUID.randomUUID();
        var input=new OrderRequests.Create(ServiceType.DINE_IN,SourceChannel.WAITER,null,null,null,null,null,
            List.of(new OrderRequests.Line(UUID.randomUUID(),BigDecimal.ONE,null)),OrderSubmissionMode.SUBMIT,target);
        var c=new CreateOrderContext(facts,input,"key"); c.normalized(new CreateOrderContext.Normalized(input,"key","hash"));
        var factory=mock(OrderFactory.class); when(factory.prepare(any(),any())).thenReturn(OrderCreationPlan.newSession(target,(short)1));
        var serving=mock(OrderSessionServingPolicy.class); var codes=mock(OrderCodeGenerator.class); when(codes.generate()).thenReturn("OD-TEST");
        var lineFactory=mock(OrderLineFactory.class); var transientOrder=new Order(); transientOrder.initialize(facts.locked().actor().restaurantId(),Instant.EPOCH);
        var line=line(transientOrder,BigDecimal.ONE,BigDecimal.TEN); line.setId(null);
        when(lineFactory.create(any(),any(),anyInt(),any())).thenReturn(List.of(line));
        var confirmation=mock(OrderConfirmationOperation.class);
        var handler=new CreateOrderPrepareHandler(factory,serving,codes,lineFactory,new OrderMoneyCalculator(),confirmation);
        var chain=new OrderChain<CreateOrderContext,OrderCreationService.Result>(List.of(handler),context-> {
            var prepared=context.prepared(); return new OrderCreationService.Result(new OrderResponseMapper().detail(prepared.order(),prepared.lines()),false);
        });
        chain.execute(c);
        assertThat(c.prepared().seating().kind()).isEqualTo(OrderCreationPlan.Kind.NEW_SESSION);
        assertThat(c.prepared().order().getId()).isNull(); assertThat(c.prepared().order().getTableSessionId()).isNull();
        assertThat(c.prepared().order().getTotalAmount()).isEqualByComparingTo("10");
        verifyNoInteractions(serving); verify(confirmation).validate(any(),any(),any());
        assertThatThrownBy(c::requirePersisted).isInstanceOf(IllegalStateException.class);
    }

    @Test void createPersistenceResolvesActualSessionIdBeforeOrderSaveAndUsesCommandTime() {
        var facts=facts(); var target=UUID.randomUUID(); var session=UUID.randomUUID();
        var request=new OrderRequests.Create(ServiceType.DINE_IN,SourceChannel.WAITER,null,null,null,null,null,List.of(),OrderSubmissionMode.SUBMIT,target);
        var c=new CreateOrderContext(facts,request,"key"); c.normalized(new CreateOrderContext.Normalized(request,"key","hash"));
        var order=new Order(); order.initialize(facts.locked().actor().restaurantId(),Instant.EPOCH); order.setServiceType(ServiceType.DINE_IN);
        var line=line(order,BigDecimal.ONE,BigDecimal.TEN); c.prepared(new CreateOrderContext.Prepared(order,List.of(line),OrderCreationPlan.newSession(target,(short)1)));
        var seating=mock(OrderSeatingCommand.class); when(seating.open(any(),any(),anyShort(),any(),any())).thenReturn(session);
        var orders=mock(OrderRepository.class); when(orders.save(any())).thenAnswer(invocation->{
            Order saved=invocation.getArgument(0); assertThat(saved.getTableSessionId()).isEqualTo(session); saved.setId(UUID.randomUUID()); return saved;
        });
        var items=mock(OrderItemRepository.class); var history=mock(OrderHistory.class); var audit=mock(OrderAudit.class); var confirmation=mock(OrderConfirmationOperation.class);
        var handler=new CreateOrderPersistHandler(orders,items,history,audit,confirmation,seating);
        var chain=new OrderChain<CreateOrderContext,OrderCreationService.Result>(List.of(handler),context->{
            context.requirePersisted(); return new OrderCreationService.Result(new OrderResponseMapper().detail(order,List.of(line)),false);
        });
        chain.execute(c);
        var sequence=inOrder(seating,orders,items,history,audit,confirmation);
        sequence.verify(seating).open(eq(facts.locked().actor().restaurantId()),eq(target),eq((short)1),eq(facts.now()),eq(facts.ip()));
        sequence.verify(orders).save(order); sequence.verify(items).saveAllAndFlush(List.of(line));
        sequence.verify(history).created(order,List.of(line),facts.locked().actor().userId(),facts.now());
        sequence.verify(audit).record(facts.locked().actor(),"ORDER_CREATED",order,null,List.of(line),facts.ip());
        sequence.verify(orders).flush(); sequence.verify(confirmation).confirm(order,List.of(line),facts.locked().actor(),facts.now(),facts.ip());
        assertThat(line.getOrderId()).isEqualTo(order.getId());
    }

    private OrderCommandFacts facts() {
        var user=new CurrentUser(UUID.randomUUID(),UUID.randomUUID(),"private-email","OWNER",Set.of("ORDER_CREATE","ORDER_UPDATE"));
        var restaurant=new Restaurant(); restaurant.setCurrencyCode("VND");
        return new OrderCommandFacts(new OrderAccessService.Locked(user,restaurant),Instant.EPOCH,"127.0.0.1");
    }
    private OrderItem line(Order order,BigDecimal quantity,BigDecimal price) {
        var line=new OrderItem(); line.initialize(order.getRestaurantId(),Instant.EPOCH); line.setId(UUID.randomUUID());
        line.setItemId(UUID.randomUUID()); line.setItemName("Original"); line.setUnit("unit"); line.setQuantity(quantity);
        line.setUnitPrice(price); line.setLineTotal(quantity.multiply(price)); line.setLineNumber(1); return line;
    }
}
