package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.menu.application.port.*;
import com.possaas.modules.order.application.port.*;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.strategy.*;
import com.possaas.modules.table.application.port.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderQrApprovalOperationTest {
    private final UUID tenant = UUID.randomUUID(), actorId = UUID.randomUUID(), itemId = UUID.randomUUID(), tableId = UUID.randomUUID(), sessionId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-14T01:00:00Z");
    private final CurrentUser actor = new CurrentUser(actorId, tenant, "private@example.test", "CASHIER", Set.of("ORDER_CREATE", "ORDER_UPDATE", "TABLE_OPEN"));
    private final OrderSeatingQuery seatingQuery = mock(OrderSeatingQuery.class);
    private final OrderSeatingCommand seatingCommand = mock(OrderSeatingCommand.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderItemRepository items = mock(OrderItemRepository.class);
    private final OrderHistory history = mock(OrderHistory.class);
    private final OrderAudit audit = mock(OrderAudit.class);
    private final OrderConfirmationOperation confirmation = mock(OrderConfirmationOperation.class);
    private final QrOrderCheckoutQuery checkout = mock(QrOrderCheckoutQuery.class);
    private final OrderMenuQuery menu = mock(OrderMenuQuery.class);
    private final OrderMoneyCalculator money = new OrderMoneyCalculator();

    @Test void qrTakeawayUsesExistingStrategyAndSharedOperationsWithoutAnyTableOrCheckoutCalls() {
        var operation = operation();
        when(orders.save(any())).thenAnswer(invocation -> { Order root = invocation.getArgument(0); root.setId(UUID.randomUUID()); return root; });
        when(items.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<OrderItem> saved = invocation.getArgument(0); saved.forEach(line -> line.setId(UUID.randomUUID())); return saved;
        });
        var prepared = operation.prepare(intent(ServiceType.TAKEAWAY, SourceChannel.QR_STATIC, null, null, "20.00"));
        verifyNoInteractions(orders, items, history, audit, seatingQuery, seatingCommand, checkout);
        var result = operation.persist(prepared);
        assertThat(result.order().serviceType()).isEqualTo(ServiceType.TAKEAWAY);
        assertThat(result.order().sourceChannel()).isEqualTo(SourceChannel.QR_STATIC);
        assertThat(result.order().tableSessionId()).isNull(); assertThat(result.order().createdBy()).isEqualTo(actorId);
        assertThat(result.order().customerName()).isEqualTo("private-name"); assertThat(result.createdOrderItemIds()).hasSize(1);
        verify(confirmation).validate(any(), any(), eq(actor)); verify(confirmation).confirm(any(), any(), eq(actor), eq(now), any());
        verifyNoInteractions(seatingQuery, seatingCommand, checkout);
    }

    @Test void qrAppendPreparationDoesNotDirtyRootAndPersistencePreservesItsStaffOriginAndCustomerMetadata() {
        var operation = operation(); var root = root(); root.setStatus(OrderStatus.SERVED);
        root.setSourceChannel(SourceChannel.WAITER); root.setCustomerName("original-name"); root.setCustomerPhone("original-phone"); root.setNote("original-note");
        root.setCreatedBy(UUID.randomUUID()); UUID originalCreator = root.getCreatedBy();
        var old = line(root, "10.00"); old.setStatus(OrderItemStatus.SERVED);
        when(orders.findAllByRestaurantIdAndTableSessionIdAndServiceTypeAndStatusIn(any(), any(), any(), any())).thenReturn(List.of(root));
        when(items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(tenant, root.getId())).thenReturn(List.of(old));
        when(seatingQuery.requireOpen(tenant, sessionId)).thenReturn(new OrderSeatingQuery.Seating(sessionId, (short)4));
        when(orders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(items.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<OrderItem> added = invocation.getArgument(0); added.forEach(line -> line.setId(UUID.randomUUID())); return added;
        });
        var prepared = operation.prepare(intent(ServiceType.DINE_IN, SourceChannel.QR_TABLE, tableId, sessionId, "20.00"));
        assertThat(root.getTotalAmount()).isEqualByComparingTo("10.00"); assertThat(root.getMutationSequence()).isZero();
        assertThat(root.getStatus()).isEqualTo(OrderStatus.SERVED); assertThat(old.getStatus()).isEqualTo(OrderItemStatus.SERVED);
        verify(items, never()).saveAllAndFlush(any()); verify(orders, never()).saveAndFlush(any());
        var result = operation.persist(prepared);
        assertThat(result.order().sourceChannel()).isEqualTo(SourceChannel.WAITER); assertThat(result.order().createdBy()).isEqualTo(originalCreator);
        assertThat(result.order().customerName()).isEqualTo("original-name"); assertThat(result.order().customerPhone()).isEqualTo("original-phone");
        assertThat(result.order().note()).isEqualTo("original-note"); assertThat(result.order().totalAmount()).isEqualByComparingTo("30.00");
        assertThat(result.order().status()).isEqualTo(OrderStatus.PREPARING);
        assertThat(result.order().items()).hasSize(2); assertThat(result.order().items().get(1).sentToKitchenAt()).isEqualTo(now);
        verify(checkout).requireAccepting(tenant, sessionId); verifyNoInteractions(seatingCommand, confirmation);
    }

    @Test void reviewedPriceMismatchRollsOutBeforeManagedRootOrOldItemsAreChanged() {
        var operation = operation(); var root = root(); root.setStatus(OrderStatus.CONFIRMED); var old = line(root, "10.00");
        when(orders.findAllByRestaurantIdAndTableSessionIdAndServiceTypeAndStatusIn(any(), any(), any(), any())).thenReturn(List.of(root));
        when(items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(tenant, root.getId())).thenReturn(List.of(old));
        when(seatingQuery.requireOpen(tenant, sessionId)).thenReturn(new OrderSeatingQuery.Seating(sessionId, (short)4));
        assertThatThrownBy(() -> operation.prepare(intent(ServiceType.DINE_IN, SourceChannel.QR_TABLE, tableId, sessionId, "19.00")))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("QR_REVIEW_CONTEXT_CHANGED"));
        assertThat(root.getMutationSequence()).isZero(); assertThat(root.getTotalAmount()).isEqualByComparingTo("10.00");
        assertThat(old.getQuantity()).isEqualByComparingTo("1"); verify(orders, never()).saveAndFlush(any()); verify(items, never()).saveAllAndFlush(any());
    }

    @Test void existingOpenStaffDraftIsNotAutoSubmittedAndPersistenceIsNotReached() {
        var operation = operation(); var root = root();
        when(orders.findAllByRestaurantIdAndTableSessionIdAndServiceTypeAndStatusIn(any(), any(), any(), any())).thenReturn(List.of(root));
        when(seatingQuery.requireOpen(tenant, sessionId)).thenReturn(new OrderSeatingQuery.Seating(sessionId, (short)4));
        assertThatThrownBy(() -> operation.prepare(intent(ServiceType.DINE_IN, SourceChannel.QR_TABLE, tableId, sessionId, "20.00")))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("QR_ORDER_DRAFT_NOT_SUBMITTED"));
        assertThat(root.getStatus()).isEqualTo(OrderStatus.OPEN); verifyNoInteractions(confirmation, history, audit, seatingCommand);
    }

    @Test void nullTargetCannotAutoSelectAConcurrentSessionAppearingAtTheSameTable() {
        var operation = operation();
        when(seatingQuery.resolveTable(tenant, tableId)).thenReturn(new OrderSeatingQuery.Existing(new OrderSeatingQuery.Seating(sessionId, (short)2)));
        assertThatThrownBy(() -> operation.prepare(intent(ServiceType.DINE_IN, SourceChannel.QR_TABLE, tableId, null, "20.00")))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("QR_REQUEST_SESSION_CHANGED"));
        verifyNoInteractions(orders, items, history, audit, confirmation, seatingCommand, checkout);
    }

    private OrderQrApprovalOperation operation() {
        var factory = new OrderFactory(new OrderCreationStrategyRegistry(List.of(new DineInOrderCreationStrategy(seatingQuery), new TakeawayOrderCreationStrategy())));
        when(menu.requireSellable(eq(tenant), any())).thenReturn(Map.of(itemId, new SellableMenuItem(itemId, "Món", "phần", new BigDecimal("20.00"))));
        return new OrderQrApprovalOperation(factory, new OrderSessionServingPolicy(orders), new OrderAppendPolicy(new OrderLifecyclePolicy(), seatingQuery),
            checkout, new OrderCodeGenerator(), new OrderLineFactory(menu, money), money, confirmation, orders, items, history,
            audit, new OrderServingStatusPolicy(), seatingCommand, new OrderResponseMapper(), mock(OrderInventoryAllocationOperation.class));
    }
    private QrApprovedOrderCommand.Intent intent(ServiceType type, SourceChannel source, UUID table, UUID session, String price) {
        var expected = new BigDecimal(price);
        return new QrApprovedOrderCommand.Intent(UUID.randomUUID(), "a".repeat(64), actor, type, source, table, session, "VND", "private-name", "private-phone", "private-note",
            List.of(new OrderRequests.Line(itemId, BigDecimal.ONE, null)),
            List.of(new QrApprovedOrderCommand.QuoteLine(itemId, "Món", "phần", BigDecimal.ONE, expected, expected)), now, "127.0.0.1");
    }
    private Order root() {
        var o = new Order(); o.initialize(tenant, now); o.setId(UUID.randomUUID()); o.setOrderCode("OD-Staff-test"); o.setServiceType(ServiceType.DINE_IN);
        o.setSourceChannel(SourceChannel.WAITER); o.setTableSessionId(sessionId); o.setCurrencyCode("VND"); o.setTotalAmount(new BigDecimal("10.00")); o.setSubtotalAmount(new BigDecimal("10.00")); return o;
    }
    private OrderItem line(Order root, String price) {
        var line = new OrderItem(); line.initialize(tenant, now); line.setId(UUID.randomUUID()); line.setOrderId(root.getId()); line.setItemId(itemId);
        line.setItemName("old-snapshot"); line.setUnit("phần"); line.setQuantity(BigDecimal.ONE); line.setUnitPrice(new BigDecimal(price)); line.setLineTotal(new BigDecimal(price)); line.setLineNumber(1); return line;
    }
}
