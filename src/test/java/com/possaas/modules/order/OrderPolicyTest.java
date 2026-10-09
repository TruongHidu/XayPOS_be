package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.dto.*;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.strategy.*;
import com.possaas.modules.table.application.port.*;
import com.possaas.modules.table.repository.TableSessionRepository;
import com.possaas.modules.table.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.access.AccessDeniedException;

class OrderPolicyTest {
    private final OrderMoneyCalculator money=new OrderMoneyCalculator();
    private final UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),session=UUID.randomUUID();
    private OrderCreationContext context(UUID sessionId,Integer guests) { return new OrderCreationContext(tenant,actor,"VND",sessionId,guests,Instant.EPOCH); }

    @Test void legacyHashFixtureIsByteCompatibleAndSubmitIsADifferentIntent() throws Exception {
        var mapper=new ObjectMapper(); var fingerprint=new OrderIdempotencyService(mapper); var normalizer=new OrderRequestNormalizer(money);
        UUID user=UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID item=UUID.fromString("00000000-0000-0000-0000-000000000002");
        // Captured pre-mode wire projection: field order, nulls and quantity scale are intentional.
        String oldJson="{\"serviceType\":\"TAKEAWAY\",\"sourceChannel\":\"CASHIER\",\"tableSessionId\":null,\"guestCount\":null,\"customerName\":null,\"customerPhone\":null,\"note\":null,\"items\":[{\"itemId\":\"00000000-0000-0000-0000-000000000002\",\"quantity\":2.000,\"note\":null}]}";
        String oldHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((user+"\n"+oldJson).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var legacy=new OrderRequests.Create(ServiceType.TAKEAWAY,SourceChannel.CASHIER,null,null,null,null,null,List.of(new OrderRequests.Line(item,new BigDecimal("2"),null)));
        assertThat(fingerprint.hash(user,normalizer.normalize(legacy))).isEqualTo(oldHash);
        var draft=new OrderRequests.Create(legacy.serviceType(),legacy.sourceChannel(),null,null,null,null,null,legacy.items(),OrderSubmissionMode.DRAFT);
        var submit=new OrderRequests.Create(legacy.serviceType(),legacy.sourceChannel(),null,null,null,null,null,legacy.items(),OrderSubmissionMode.SUBMIT);
        assertThat(fingerprint.hash(user,normalizer.normalize(draft))).isEqualTo(oldHash);
        assertThat(fingerprint.hash(user,normalizer.normalize(submit))).isNotEqualTo(oldHash);
    }
    @Test void appendFingerprintIncludesVersionTargetActorModeAndOrderedNormalizedLines() {
        var normalize=new OrderRequestNormalizer(money); var fingerprint=new OrderIdempotencyService(new ObjectMapper());
        var lines=List.of(new OrderRequests.Line(UUID.randomUUID(),BigDecimal.ONE," hi "));
        var command=normalize.normalize(new OrderRequests.AddItems(2L,lines,OrderSubmissionMode.SUBMIT));
        String hash=fingerprint.appendHash(actor,session,command);
        var equivalent=normalize.normalize(new OrderRequests.AddItems(2L,List.of(new OrderRequests.Line(lines.get(0).itemId(),new BigDecimal("1.000"),"hi")),OrderSubmissionMode.SUBMIT));
        assertThat(fingerprint.appendHash(actor,session,equivalent)).isEqualTo(hash);
        assertThat(fingerprint.appendHash(UUID.randomUUID(),session,command)).isNotEqualTo(hash);
        assertThat(fingerprint.appendHash(actor,UUID.randomUUID(),command)).isNotEqualTo(hash);
        assertThat(fingerprint.appendHash(actor,session,normalize.normalize(new OrderRequests.AddItems(3L,lines,OrderSubmissionMode.SUBMIT)))).isNotEqualTo(hash);
        assertThat(fingerprint.appendHash(actor,session,normalize.normalize(new OrderRequests.AddItems(2L,lines)))).isNotEqualTo(hash);
    }
    @Test void submissionPermissionAndAppendBoundaryAreIndependentOfOldEdits() {
        var updater=new CurrentUser(actor,tenant,"fixture@example.test","OWNER",Set.of("ORDER_CREATE"));
        var submission=new OrderSubmissionPolicy(); submission.requireCreate(updater,OrderSubmissionMode.DRAFT);
        assertThatThrownBy(()->submission.requireCreate(updater,OrderSubmissionMode.SUBMIT)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->submission.requireAppend(updater)).isInstanceOf(AccessDeniedException.class);
        var lifecycle=new OrderLifecyclePolicy(); var seating=mock(OrderSeatingQuery.class);
        var append=new OrderAppendPolicy(lifecycle,seating); var order=new Order(); order.setCurrencyCode("VND"); order.setServiceType(ServiceType.TAKEAWAY);
        order.setStatus(OrderStatus.CONFIRMED); append.requireAppendable(order,OrderSubmissionMode.SUBMIT,"VND");
        assertThatThrownBy(()->lifecycle.requireEditable(order)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->append.requireAppendable(order,OrderSubmissionMode.DRAFT,"VND")).isInstanceOf(BusinessException.class);
        order.setStatus(OrderStatus.PREPARING);
        assertThatThrownBy(()->append.requireAppendable(order,OrderSubmissionMode.SUBMIT,"VND")).isInstanceOf(BusinessException.class);
    }

    @Test void strategiesResolveTheirOwnSeatingRulesAndFactoryDelegates() {
        var seating=mock(OrderSeatingQuery.class); when(seating.requireOpen(tenant,session)).thenReturn(new OrderSeatingQuery.Seating(session,(short)4));
        var dineIn=spy(new DineInOrderCreationStrategy(seating)); var takeaway=new TakeawayOrderCreationStrategy();
        var factory=new OrderFactory(new OrderCreationStrategyRegistry(List.of(dineIn,takeaway)));
        assertThat(factory.prepare(ServiceType.DINE_IN,context(session,null)).guestCount()).isEqualTo((short)4);
        assertThat(factory.prepare(ServiceType.DINE_IN,context(session,2)).guestCount()).isEqualTo((short)2);
        assertThat(factory.prepare(ServiceType.TAKEAWAY,context(null,null))).isEqualTo(new OrderCreationPlan(null,(short)1));
        verify(dineIn).prepare(context(session,null));
        assertThatThrownBy(()->factory.prepare(ServiceType.DINE_IN,context(null,null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->factory.prepare(ServiceType.TAKEAWAY,context(session,1))).isInstanceOf(BusinessException.class);
    }
    @Test void registryRejectsDuplicateMissingAndUnsupportedStrategies() {
        var dineIn=new DineInOrderCreationStrategy(mock(OrderSeatingQuery.class)); var takeaway=new TakeawayOrderCreationStrategy();
        assertThatThrownBy(()->new OrderCreationStrategyRegistry(List.of(dineIn))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new OrderCreationStrategyRegistry(List.of(dineIn,dineIn,takeaway))).isInstanceOf(IllegalStateException.class);
        var registry=new OrderCreationStrategyRegistry(List.of(dineIn,takeaway));
        assertThatThrownBy(()->registry.get(null)).isInstanceOf(BusinessException.class);
    }
    @Test void moneyRoundsPerLineAndChecksQuantityAndOverflow() {
        assertThat(money.line(new BigDecimal("0.001"),new BigDecimal("15.00"))).isEqualByComparingTo("0.02");
        assertThat(money.line(new BigDecimal("5"),BigDecimal.ZERO)).isEqualByComparingTo("0.00");
        assertThat(money.quantity(new BigDecimal("2.0"))).isEqualTo(new BigDecimal("2.000"));
        for(String value:List.of("0","-1","0.0001","1000000000")) assertThatThrownBy(()->money.quantity(new BigDecimal(value))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->money.line(new BigDecimal("2"),new BigDecimal("999999999999.99"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("ORDER_AMOUNT_LIMIT_EXCEEDED"));
    }
    @Test void cancelledLinesAreExcludedWithoutErasingTheirSnapshots() {
        var order=new Order(); var live=new OrderItem(); live.setLineTotal(new BigDecimal("10.01"));
        var cancelled=new OrderItem(); cancelled.setStatus(OrderItemStatus.CANCELLED); cancelled.setLineTotal(new BigDecimal("20.00"));
        money.recalculate(order,List.of(live,cancelled)); assertThat(order.getTotalAmount()).isEqualByComparingTo("10.01");
        assertThat(cancelled.getLineTotal()).isEqualByComparingTo("20.00");
    }
    @Test void lifecycleSeparatesPaymentVersionAndLineCancellationPermissions() {
        var policy=new OrderLifecyclePolicy(); var order=new Order(); var line=new OrderItem();
        policy.requireEditable(order); policy.requireVersion(order,0);
        assertThatThrownBy(()->policy.requireVersion(order,1)).isInstanceOf(BusinessException.class);
        var updater=new CurrentUser(actor,tenant,"fixture@example.test","WAITER",Set.of("ORDER_UPDATE"));
        policy.requireLineCancellationPermission(order,updater); order.setConfirmedAt(Instant.EPOCH);
        assertThatThrownBy(()->policy.requireLineCancellationPermission(order,updater)).isInstanceOf(AccessDeniedException.class);
        order.setStatus(OrderStatus.CONFIRMED); line.setStatus(OrderItemStatus.COOKING);
        assertThatThrownBy(()->policy.requireCancellable(order,List.of(line))).isInstanceOf(BusinessException.class);
        line.setStatus(OrderItemStatus.PENDING); order.setPaidAmount(BigDecimal.ONE);
        assertThatThrownBy(()->policy.requireCancellable(order,List.of(line))).isInstanceOf(BusinessException.class);
    }
    @Test void fingerprintsNormalizeEquivalentRequestsAndIncludeActorAndPayload() {
        var normalize=new OrderRequestNormalizer(money); var idempotency=new OrderIdempotencyService(new ObjectMapper());
        UUID item=UUID.randomUUID();
        var one=new OrderRequests.Create(ServiceType.TAKEAWAY,SourceChannel.CASHIER,null,null," Name ",null," note ",List.of(new OrderRequests.Line(item,new BigDecimal("2")," hi ")));
        var two=new OrderRequests.Create(ServiceType.TAKEAWAY,SourceChannel.CASHIER,null,null,"Name",null,"note",List.of(new OrderRequests.Line(item,new BigDecimal("2.0"),"hi")));
        String hash=idempotency.hash(actor,normalize.normalize(one));
        assertThat(idempotency.hash(actor,normalize.normalize(two))).isEqualTo(hash);
        assertThat(idempotency.hash(UUID.randomUUID(),normalize.normalize(two))).isNotEqualTo(hash);
        var order=new Order(); order.setCreatedBy(actor); order.setRequestHash(hash);
        idempotency.requireReplayMatch(order,actor,hash);
        assertThatThrownBy(()->idempotency.requireReplayMatch(order,actor,"different")).isInstanceOf(BusinessException.class);
        for(String key:Arrays.asList(null,""," ","x".repeat(101))) assertThatThrownBy(()->idempotency.key(key)).isInstanceOf(BusinessException.class);
    }
    @Test void staffSourceAndSearchOptionsCannotEnableQrOrInternalSorting() {
        var normalize=new OrderRequestNormalizer(money);
        for(var channel:List.of(SourceChannel.QR_TABLE,SourceChannel.QR_STATIC)) {
            var request=new OrderRequests.Create(ServiceType.TAKEAWAY,channel,null,null,null,null,null,List.of(new OrderRequests.Line(UUID.randomUUID(),BigDecimal.ONE,null)));
            assertThatThrownBy(()->normalize.normalize(request)).isInstanceOf(BusinessException.class);
        }
        var search=new OrderSearch(); assertThat(search.pageable().getSort().toString()).isEqualTo("createdAt: DESC,id: ASC");
        search.setSortBy("requestHash"); assertThatThrownBy(search::pageable).isInstanceOf(BusinessException.class);
    }
    @Test void tableSessionServiceCannotStartWithoutRealUsagePolicy() {
        new ApplicationContextRunner().withBean(TableSessionCommandService.class)
            .withBean(TableAccessService.class,()->mock(TableAccessService.class))
            .withBean(TableSessionRepository.class,()->mock(TableSessionRepository.class))
            .withBean(TableValidationPolicy.class,TableValidationPolicy::new)
            .withBean(TableLifecyclePolicy.class,TableLifecyclePolicy::new)
            .withBean(TableSessionLifecyclePolicy.class,TableSessionLifecyclePolicy::new)
            .withBean(TableResponseMapper.class,()->mock(TableResponseMapper.class))
            .withBean(TableAudit.class,()->mock(TableAudit.class))
            .withBean(TableSessionOpeningOperation.class,()->mock(TableSessionOpeningOperation.class))
            .withBean(Clock.class,()->Clock.fixed(Instant.EPOCH,ZoneOffset.UTC))
            .run(context->assertThat(context).hasFailed());
    }
}
