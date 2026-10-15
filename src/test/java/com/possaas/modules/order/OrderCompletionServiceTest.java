package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderItem;
import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.order.service.OrderAccessService;
import com.possaas.modules.order.service.OrderAudit;
import com.possaas.modules.order.service.OrderCompletionPolicy;
import com.possaas.modules.order.service.OrderCompletionService;
import com.possaas.modules.order.service.OrderLifecyclePolicy;
import com.possaas.modules.order.service.OrderResponseMapper;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.table.application.port.OrderSessionClosureCommand;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

class OrderCompletionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00.123456789Z");
    private static final Instant MICRO_NOW = Instant.parse("2026-10-14T00:00:00.123456Z");
    private final UUID tenant = UUID.randomUUID(), orderId = UUID.randomUUID(), sessionId = UUID.randomUUID();
    private final CurrentUser actor = new CurrentUser(UUID.randomUUID(), tenant, "staff@example.test", "CUSTOM",
        Set.of("ORDER_COMPLETE", "TABLE_CLOSE"));
    private OrderAccessService access;
    private OrderRepository orders;
    private OrderItemRepository items;
    private OrderSessionClosureCommand closure;
    private OrderAudit audit;
    private OrderResponseMapper mapper;
    private OrderCompletionService service;
    private Order order;
    private List<OrderItem> lines;

    @BeforeEach
    void setup() {
        access = mock(OrderAccessService.class); orders = mock(OrderRepository.class);
        items = mock(OrderItemRepository.class); closure = mock(OrderSessionClosureCommand.class);
        audit = mock(OrderAudit.class); mapper = mock(OrderResponseMapper.class);
        service = new OrderCompletionService(access, orders, items, new OrderLifecyclePolicy(),
            new OrderCompletionPolicy(), closure, audit, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        order = new Order(); order.initialize(tenant, NOW.minusSeconds(60)); order.setId(orderId);
        order.setServiceType(ServiceType.DINE_IN); order.setTableSessionId(sessionId);
        order.setStatus(OrderStatus.SERVED); order.setPaymentStatus(OrderPaymentStatus.PAID);
        order.setTotalAmount(new BigDecimal("10.00")); order.setPaidAmount(new BigDecimal("10.0"));
        order.setVersion(3); order.setMutationSequence(7);
        var served = new OrderItem(); served.setStatus(OrderItemStatus.SERVED);
        var cancelled = new OrderItem(); cancelled.setStatus(OrderItemStatus.CANCELLED);
        lines = List.of(served, cancelled);
        when(access.lock()).thenReturn(new OrderAccessService.Locked(actor, new Restaurant()));
        when(access.order(tenant, orderId)).thenReturn(order);
        when(items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(tenant, orderId)).thenReturn(lines);
    }

    @Test
    void missingDineInSessionIsABusinessConflictBeforePersistenceOrTableAccess() {
        // PostgreSQL correctly forbids this row; exercise corrupted upstream state at the service boundary.
        order.setTableSessionId(null);
        assertError("INVALID_ORDER_SEATING", () -> service.complete(orderId, 3, "127.0.0.1"));
        verifyNoInteractions(closure, audit, orders);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SERVED);
    }

    @Test
    void missingOrForeignSessionFromTablePortStaysA404WithoutWritingOrder() {
        when(closure.isClosed(tenant, sessionId)).thenThrow(new ResourceNotFoundException("TABLE_SESSION_NOT_FOUND", "Not found"));
        assertError("TABLE_SESSION_NOT_FOUND", () -> service.complete(orderId, 3, "127.0.0.1"));
        verifyNoInteractions(orders, audit);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SERVED);
    }

    @Test
    void tablePermissionCheckRunsBeforeAlreadyCompletedNoOp() {
        order.setStatus(OrderStatus.COMPLETED);
        when(closure.isClosed(tenant, sessionId)).thenThrow(new AccessDeniedException("TABLE_CLOSE denied"));
        assertThatThrownBy(() -> service.complete(orderId, 0, "127.0.0.1")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(orders, audit, mapper);
    }

    @Test
    void incompleteOrderCannotCompleteAgainstAnAlreadyClosedSession() {
        when(closure.isClosed(tenant, sessionId)).thenReturn(true);
        assertError("TABLE_SESSION_NOT_OPEN", () -> service.complete(orderId, 3, "127.0.0.1"));
        verifyNoInteractions(orders, audit);
        verify(closure, never()).close(any(), any(), any(), any());
    }

    @Test
    void cancelledSessionDoesNotBecomeClosedEvenForLegacyCompletedOrder() {
        order.setStatus(OrderStatus.COMPLETED);
        when(closure.isClosed(tenant, sessionId)).thenThrow(new ConflictException("TABLE_SESSION_NOT_OPEN", "Cancelled"));
        assertError("TABLE_SESSION_NOT_OPEN", () -> service.complete(orderId, 0, "127.0.0.1"));
        verifyNoInteractions(orders, audit);
        verify(closure, never()).close(any(), any(), any(), any());
    }

    @Test
    void legacyCompletedOpenRepairsOnlySessionAndPreservesCompletionTimestampMutationAndVersion() {
        var oldTime = NOW.minusSeconds(30); order.setStatus(OrderStatus.COMPLETED); order.setCompletedAt(oldTime);
        service.complete(orderId, 0, "127.0.0.1");
        verify(closure).close(tenant, sessionId, MICRO_NOW, "127.0.0.1");
        verifyNoInteractions(orders, audit);
        assertThat(order.getCompletedAt()).isEqualTo(oldTime);
        assertThat(order.getVersion()).isEqualTo(3);
        assertThat(order.getMutationSequence()).isEqualTo(7);
    }

    @Test
    void legacyRepairDoesNotCloseAnInvalidPaymentAggregate() {
        order.setStatus(OrderStatus.COMPLETED); order.setPaidAmount(BigDecimal.ZERO);
        assertError("ORDER_NOT_PAID", () -> service.complete(orderId, 0, "127.0.0.1"));
        verify(closure, never()).close(any(), any(), any(), any());
        verifyNoInteractions(orders, audit);
    }

    @Test
    void legacyRepairDoesNotCloseAnUnservedAggregate() {
        order.setStatus(OrderStatus.COMPLETED); lines.getFirst().setStatus(OrderItemStatus.READY);
        assertError("ORDER_NOT_READY_FOR_COMPLETION", () -> service.complete(orderId, 0, "127.0.0.1"));
        verify(closure, never()).close(any(), any(), any(), any());
        verifyNoInteractions(orders, audit);
    }

    @Test
    void legacyCompletedClosedIsAnIdempotentNoOpWithOldVersion() {
        var oldTime = NOW.minusSeconds(30); order.setStatus(OrderStatus.COMPLETED); order.setCompletedAt(oldTime);
        when(closure.isClosed(tenant, sessionId)).thenReturn(true);
        service.complete(orderId, 0, "127.0.0.1");
        verify(closure, never()).close(any(), any(), any(), any());
        verifyNoInteractions(orders, audit);
        assertThat(order.getCompletedAt()).isEqualTo(oldTime);
        assertThat(order.getMutationSequence()).isEqualTo(7);
    }

    @Test
    void takeawayIgnoresTablePortAndKeepsOrderCompletionTimeAtDatabasePrecision() {
        order.setServiceType(ServiceType.TAKEAWAY); order.setTableSessionId(null);
        service.complete(orderId, 3, "127.0.0.1");
        verifyNoInteractions(closure);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getCompletedAt()).isEqualTo(MICRO_NOW);
        assertThat(order.getMutationSequence()).isEqualTo(8);
        assertThat(lines).extracting(OrderItem::getStatus).containsExactly(OrderItemStatus.SERVED, OrderItemStatus.CANCELLED);
    }

    @Test
    void deniedOrderCompleteDoesNotCallTablePortOrWriteEvenForNoOp() {
        order.setStatus(OrderStatus.COMPLETED);
        var denied = new CurrentUser(actor.userId(), tenant, actor.email(), "CUSTOM", Set.of("TABLE_CLOSE"));
        when(access.lock()).thenReturn(new OrderAccessService.Locked(denied, new Restaurant()));
        assertThatThrownBy(() -> service.complete(orderId, 0, "127.0.0.1")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(closure, orders, audit);
    }

    @Test
    void closureFailureIsPropagatedAndDoesNotCreateOrderSuccessAudit() {
        doThrow(new ConflictException("TABLE_SESSION_NOT_READY_TO_CLOSE", "Other order unpaid"))
            .when(closure).close(eq(tenant), eq(sessionId), any(), any());
        assertError("TABLE_SESSION_NOT_READY_TO_CLOSE", () -> service.complete(orderId, 3, "127.0.0.1"));
        // The PostgreSQL integration tests verify transaction rollback after this flushed write.
        verify(orders).saveAndFlush(order);
        verify(audit, never()).record(any(), eq("ORDER_COMPLETED"), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "CANCELLED"})
    void draftAndCancelledOrdersCannotCompleteOrCloseSession(String status) {
        order.setStatus(OrderStatus.valueOf(status));
        assertError("ORDER_NOT_READY_FOR_COMPLETION", () -> service.complete(orderId, 3, "127.0.0.1"));
        verifyNoInteractions(orders, audit);
        verify(closure, never()).close(any(), any(), any(), any());
    }

    private void assertError(String code, Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(code));
    }
}
