package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.strategy.*;
import com.possaas.modules.table.application.port.OrderSeatingQuery;
import com.possaas.modules.table.service.TableSessionOpeningPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class OrderTablePolicyTest {
    private final UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),table=UUID.randomUUID(),session=UUID.randomUUID();
    private final OrderRequestNormalizer normalizer=new OrderRequestNormalizer(new OrderMoneyCalculator());
    private OrderRequests.Create request(ServiceType type,UUID sessionId,UUID tableId,OrderSubmissionMode mode) {
        return new OrderRequests.Create(type,SourceChannel.WAITER,sessionId,null," Name ",null," note ",
            List.of(new OrderRequests.Line(UUID.fromString("00000000-0000-0000-0000-000000000002"),new BigDecimal("2")," hi ")),mode,tableId);
    }

    @Test void oldConstructorsAndNullTableReferencePreserveLegacyModesAndHashes() {
        var legacy=request(ServiceType.TAKEAWAY,null,null,null);
        var eight=new OrderRequests.Create(legacy.serviceType(),legacy.sourceChannel(),null,null," Name ",null," note ",legacy.items());
        var nine=new OrderRequests.Create(legacy.serviceType(),legacy.sourceChannel(),null,null," Name ",null," note ",legacy.items(),OrderSubmissionMode.SUBMIT);
        assertThat(eight.tableId()).isNull(); assertThat(eight.effectiveMode()).isEqualTo(OrderSubmissionMode.DRAFT);
        assertThat(nine.tableId()).isNull(); assertThat(nine.effectiveMode()).isEqualTo(OrderSubmissionMode.SUBMIT);
        var hash=new OrderIdempotencyService(new ObjectMapper());
        assertThat(hash.hash(actor,normalizer.normalize(eight))).isEqualTo(hash.hash(actor,normalizer.normalize(legacy)));
        assertThat(hash.hash(actor,normalizer.normalize(nine))).isEqualTo(hash.hash(actor,normalizer.normalize(request(ServiceType.TAKEAWAY,null,null,OrderSubmissionMode.SUBMIT))));
    }

    @Test void legacySubmitFingerprintStillMatchesExactV1WireEnvelope() throws Exception {
        UUID user=UUID.fromString("00000000-0000-0000-0000-000000000001");
        String payload="{\"operation\":\"create-submit-v1\",\"request\":{\"serviceType\":\"TAKEAWAY\",\"sourceChannel\":\"WAITER\",\"tableSessionId\":null,\"guestCount\":null,\"customerName\":\"Name\",\"customerPhone\":null,\"note\":\"note\",\"items\":[{\"itemId\":\"00000000-0000-0000-0000-000000000002\",\"quantity\":2.000,\"note\":\"hi\"}]}}";
        assertThat(new OrderIdempotencyService(new ObjectMapper()).hash(user,normalizer.normalize(request(ServiceType.TAKEAWAY,null,null,OrderSubmissionMode.SUBMIT))))
            .isEqualTo(digest(user,payload));
    }

    @Test void tableNormalizationPreservesOriginalIntentAndStableNewNamespace() throws Exception {
        UUID user=UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID target=UUID.fromString("00000000-0000-0000-0000-000000000003");
        var normalized=normalizer.normalize(request(ServiceType.DINE_IN,null,target,OrderSubmissionMode.SUBMIT));
        assertThat(normalized.tableId()).isEqualTo(target); assertThat(normalized.tableSessionId()).isNull(); assertThat(normalized.guestCount()).isNull();
        String payload="{\"operation\":\"create-table-submit-v1\",\"tableId\":\"00000000-0000-0000-0000-000000000003\",\"serviceType\":\"DINE_IN\",\"sourceChannel\":\"WAITER\",\"submissionMode\":\"SUBMIT\",\"guestCount\":null,\"customerName\":\"Name\",\"customerPhone\":null,\"note\":\"note\",\"items\":[{\"itemId\":\"00000000-0000-0000-0000-000000000002\",\"quantity\":2.000,\"note\":\"hi\"}]}";
        var hash=new OrderIdempotencyService(new ObjectMapper());
        assertThat(hash.hash(user,normalized)).isEqualTo(digest(user,payload));
        var equivalent=new OrderRequests.Create(ServiceType.DINE_IN,SourceChannel.WAITER,null,null,"Name",null,"note",
            List.of(new OrderRequests.Line(normalized.items().getFirst().itemId(),new BigDecimal("2.0"),"hi")),OrderSubmissionMode.SUBMIT,target);
        assertThat(hash.hash(user,normalizer.normalize(equivalent))).isEqualTo(hash.hash(user,normalized));
        assertThat(hash.hash(user,normalizer.normalize(request(ServiceType.DINE_IN,null,UUID.randomUUID(),OrderSubmissionMode.SUBMIT)))).isNotEqualTo(hash.hash(user,normalized));
        assertThat(hash.hash(actor,normalized)).isNotEqualTo(hash.hash(user,normalized));
        assertThat(hash.hash(user,normalizer.normalize(request(ServiceType.DINE_IN,session,null,OrderSubmissionMode.SUBMIT)))).isNotEqualTo(hash.hash(user,normalized));
    }

    @Test void shapeValidationRejectsConflictingMissingAndUnsupportedSeatingBeforeAnyDatabaseWork() {
        assertCode(request(ServiceType.DINE_IN,session,table,OrderSubmissionMode.SUBMIT),"AMBIGUOUS_ORDER_SEATING");
        assertCode(request(ServiceType.DINE_IN,null,null,OrderSubmissionMode.SUBMIT),"DINE_IN_SESSION_REQUIRED");
        for(var mode:Arrays.asList(null,OrderSubmissionMode.DRAFT)) assertCode(request(ServiceType.DINE_IN,null,table,mode),"TABLE_ORDER_REQUIRES_SUBMIT");
        assertCode(request(ServiceType.TAKEAWAY,null,table,OrderSubmissionMode.SUBMIT),"TAKEAWAY_TABLE_NOT_ALLOWED");
        assertCode(request(ServiceType.TAKEAWAY,session,null,OrderSubmissionMode.SUBMIT),"TAKEAWAY_SESSION_NOT_ALLOWED");
    }

    @Test void dineInStrategyPreparesExistingOrNewSeatingWithoutGeneratingOrPersistingSession() {
        var seating=mock(OrderSeatingQuery.class);
        var strategy=new DineInOrderCreationStrategy(seating);
        var context=new OrderCreationContext(tenant,actor,"VND",null,null,Instant.EPOCH,table);
        when(seating.resolveTable(tenant,table)).thenReturn(new OrderSeatingQuery.Existing(new OrderSeatingQuery.Seating(session,(short)4)));
        var existing=strategy.prepare(context);
        assertThat(existing.kind()).isEqualTo(OrderCreationPlan.Kind.EXISTING_SESSION); assertThat(existing.tableSessionId()).isEqualTo(session);
        assertThat(existing.guestCount()).isEqualTo((short)4);
        when(seating.resolveTable(tenant,table)).thenReturn(new OrderSeatingQuery.Unoccupied(table));
        var fresh=strategy.prepare(context);
        assertThat(fresh.kind()).isEqualTo(OrderCreationPlan.Kind.NEW_SESSION); assertThat(fresh.tableId()).isEqualTo(table);
        assertThat(fresh.tableSessionId()).isNull(); assertThat(fresh.guestCount()).isEqualTo((short)1);
        assertThat(strategy.prepare(new OrderCreationContext(tenant,actor,"VND",null,10,Instant.EPOCH,table)).guestCount()).isEqualTo((short)10);
        verify(seating,never()).requireOpen(any(),any());
    }

    @Test void creationPlanRejectsContradictoryTargetsAndInvalidGuestCount() {
        assertThatThrownBy(()->new OrderCreationPlan(OrderCreationPlan.Kind.NO_SEATING,session,null,(short)1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OrderCreationPlan(OrderCreationPlan.Kind.EXISTING_SESSION,null,null,(short)1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OrderCreationPlan(OrderCreationPlan.Kind.NEW_SESSION,session,table,(short)1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OrderCreationPlan.newSession(null,(short)1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OrderCreationPlan.newSession(table,(short)0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void tableOpeningPermissionIsIndependentOfRoleOrderPermissionsAndForeignScope() {
        var policy=new TableSessionOpeningPolicy();
        policy.requireAllowed(new CurrentUser(actor,tenant,"private@example.test","CASHIER",Set.of("TABLE_OPEN")),tenant);
        assertThatThrownBy(()->policy.requireAllowed(new CurrentUser(actor,tenant,"private@example.test","OWNER",Set.of("ORDER_CREATE","ORDER_UPDATE")),tenant)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->policy.requireAllowed(new CurrentUser(actor,tenant,"private@example.test","OWNER",Set.of("TABLE_OPEN")),UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
    }

    private void assertCode(OrderRequests.Create request,String code) {
        assertThatThrownBy(()->normalizer.normalize(request)).isInstanceOfSatisfying(BusinessException.class,failure->assertThat(failure.getCode()).isEqualTo(code));
    }
    private String digest(UUID user,String payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((user+"\n"+payload).getBytes(StandardCharsets.UTF_8)));
    }
}
