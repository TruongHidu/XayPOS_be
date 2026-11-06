package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.order.service.chain.append.*;
import com.possaas.modules.order.service.chain.create.*;
import com.possaas.modules.qrorder.dto.*;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.service.QrStaffResponseMapper;
import com.possaas.modules.qrorder.service.chain.*;
import com.possaas.modules.restaurant.entity.Restaurant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderInventoryReplayValidationTest {
    @ParameterizedTest @ValueSource(strings = {"create", "append", "qr"})
    void acceptedRequestReplayValidatesCurrentInventoryWithoutReenteringAcceptance(String route) {
        exercise(route, false);
    }

    @ParameterizedTest @ValueSource(strings = {"create", "append", "qr"})
    void durableAllocationConflictCannotBeHiddenByIdempotencyReplay(String route) {
        exercise(route, true);
    }

    private void exercise(String route, boolean corrupted) {
        var tenant = UUID.randomUUID(); var now = Instant.parse("2026-10-20T08:00:00Z");
        var actor = new CurrentUser(UUID.randomUUID(), tenant, "waiter@example.test", "WAITER",
                Set.of("ORDER_CREATE", "ORDER_UPDATE"));
        var restaurant = new Restaurant(); restaurant.setId(tenant);
        var locked = new OrderAccessService.Locked(actor, restaurant);
        var facts = new OrderCommandFacts(locked, now, "ip");
        var root = new Order(); root.initialize(tenant, now); root.setId(UUID.randomUUID());
        root.setStatus(OrderStatus.COMPLETED); root.setServiceType(ServiceType.TAKEAWAY);
        root.setSourceChannel(SourceChannel.WAITER); root.setCurrencyCode("VND");
        var line = new OrderItem(); line.initialize(tenant, now); line.setId(UUID.randomUUID());
        line.setOrderId(root.getId()); line.setItemId(UUID.randomUUID()); line.setUnit("portion");
        line.setQuantity(BigDecimal.ONE); line.setUnitPrice(BigDecimal.TEN); line.setLineTotal(BigDecimal.TEN);
        line.setStatus(OrderItemStatus.SERVED); line.setSentToKitchenAt(now);
        var lines = List.of(line);
        var access = mock(OrderAccessService.class); var orders = mock(OrderRepository.class);
        var items = mock(OrderItemRepository.class); var normalizer = mock(OrderRequestNormalizer.class);
        var submission = mock(OrderSubmissionPolicy.class); var keys = mock(OrderIdempotencyService.class);
        var inventory = mock(OrderInventoryAllocationOperation.class);
        var conflict = new ConflictException("INVENTORY_ALLOCATION_CONFLICT", "Corrupted allocation");
        if (corrupted) doThrow(conflict).when(inventory).validate(root, lines);
        when(items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(tenant, root.getId())).thenReturn(lines);
        when(access.order(tenant, root.getId())).thenReturn(root);
        var mapper = new OrderResponseMapper();
        Supplier<Boolean> replay;

        if (route.equals("create")) {
            var request = new OrderRequests.Create(ServiceType.TAKEAWAY, SourceChannel.WAITER, null,
                    null, null, null, null, List.of(new OrderRequests.Line(line.getItemId(), BigDecimal.ONE, null)),
                    OrderSubmissionMode.SUBMIT);
            when(normalizer.normalize(request)).thenReturn(request);
            when(keys.key("key")).thenReturn("key"); when(keys.hash(actor.userId(), request)).thenReturn("hash");
            when(orders.findByRestaurantIdAndIdempotencyKey(tenant, "key")).thenReturn(Optional.of(root));
            var handler = new CreateOrderReplayHandler(access, normalizer, submission, keys, orders, items, mapper, inventory);
            var chain = new OrderChain<CreateOrderContext, OrderCreationService.Result>(List.of(handler),
                    context -> { throw new AssertionError("Replay reached acceptance"); });
            replay = () -> chain.execute(new CreateOrderContext(facts, request, "key")).replay();
        } else if (route.equals("append")) {
            var request = new OrderRequests.AddItems(0L,
                    List.of(new OrderRequests.Line(line.getItemId(), BigDecimal.ONE, null)), OrderSubmissionMode.SUBMIT);
            when(normalizer.normalize(request)).thenReturn(request);
            when(keys.key("key")).thenReturn("key");
            when(keys.appendHash(actor.userId(), root.getId(), request)).thenReturn("hash");
            var submissions = mock(OrderAppendIdempotency.class);
            when(submissions.replay(tenant, root.getId(), actor.userId(), "key", "hash")).thenReturn(true);
            var handler = new AddOrderItemsReplayHandler(access, submission, normalizer, keys, submissions, items, mapper, inventory);
            var chain = new OrderChain<AddOrderItemsContext, OrderAppendService.Result>(List.of(handler),
                    context -> { throw new AssertionError("Replay reached acceptance"); });
            replay = () -> chain.execute(new AddOrderItemsContext(facts, root.getId(), request, "key")).replay();
        } else {
            var request = new QrOrderRequest(); request.initialize(tenant, now); request.setId(UUID.randomUUID());
            request.setEntryType(QrEntryType.RESTAURANT); request.setStatus(QrRequestStatus.APPROVED);
            request.setApprovedOrderId(root.getId());
            var command = new QrStaffRequests.Approve(); command.setTargetSessionId(null); command.setExpectedRequestVersion(0L);
            var handler = new QrApprovalReplayHandler(access, items, mapper, new QrStaffResponseMapper(), inventory);
            var chain = new OrderChain<QrApprovalContext, QrApprovalResponse>(List.of(handler),
                    context -> { throw new AssertionError("Replay reached acceptance"); });
            replay = () -> chain.execute(new QrApprovalContext(locked, now, "ip", command, request, List.of())).replay();
        }

        if (corrupted) assertThatThrownBy(replay::get).isSameAs(conflict);
        else assertThat(replay.get()).isTrue();
        verify(inventory).validate(root, lines);
        verifyNoMoreInteractions(inventory);
        verify(items, never()).saveAll(any()); verify(orders, never()).saveAndFlush(any());
        assertThat(root.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(root.getMutationSequence()).isZero();
    }
}
