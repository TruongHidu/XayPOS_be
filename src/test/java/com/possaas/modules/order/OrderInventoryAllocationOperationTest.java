package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.AcceptedItem;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.LineState;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class OrderInventoryAllocationOperationTest {
    final UUID tenant = UUID.randomUUID(), orderId = UUID.randomUUID();
    final Instant now = Instant.parse("2026-10-20T08:00:00Z");
    final CurrentUser actor = new CurrentUser(UUID.randomUUID(), tenant, "staff@example.test", "WAITER",
            Set.of("ORDER_CREATE", "ORDER_UPDATE"));
    final OrderRecipeSnapshotService capture = mock(OrderRecipeSnapshotService.class);
    final OrderItemRecipeSnapshotRepository snapshots = mock(OrderItemRecipeSnapshotRepository.class);
    final OrderItemRecipeUsageRepository usages = mock(OrderItemRecipeUsageRepository.class);
    final OrderInventoryAllocationCommand inventory = mock(OrderInventoryAllocationCommand.class);
    final OrderInventoryAllocationOperation operation = new OrderInventoryAllocationOperation(
            capture, snapshots, usages, new OrderRecipeSnapshotPolicy(), inventory);

    @Test void acceptanceSendsOnlyTrackedImmutableUsageQuantitiesInOneBatchAfterCapture() {
        var root = order(); var first = line(); var second = line();
        var firstSnapshot = snapshot(first); var secondSnapshot = snapshot(second);
        var tracked = usage(firstSnapshot, true, "315.001");
        var untracked = usage(firstSnapshot, false, "999.999");
        var other = usage(secondSnapshot, true, "0.001");
        var entitlement=mock(com.possaas.modules.subscription.domain.CurrentEntitlement.class);
        when(entitlement.hasFeature("INVENTORY_MANAGEMENT")).thenReturn(true);
        when(capture.capture(root,List.of(first,second),actor,now,"ip")).thenReturn(entitlement);
        when(snapshots.findAllByRestaurantIdAndOrderItemIdIn(eq(tenant), anyCollection()))
                .thenReturn(List.of(secondSnapshot, firstSnapshot));
        when(usages.findAllByRestaurantIdAndRecipeSnapshotIdInOrderByRecipeSnapshotIdAscIngredientIdAsc(
                eq(tenant), anyCollection())).thenReturn(List.of(tracked, untracked, other));

        operation.accept(root, List.of(first, second), actor, now, "ip");

        @SuppressWarnings("unchecked") ArgumentCaptor<List<AcceptedItem>> sent = ArgumentCaptor.forClass(List.class);
        var sequence = inOrder(capture, snapshots, usages, inventory);
        sequence.verify(capture).capture(root, List.of(first, second), actor, now, "ip");
        sequence.verify(snapshots).findAllByRestaurantIdAndOrderItemIdIn(tenant, List.of(first.getId(), second.getId()));
        sequence.verify(usages).findAllByRestaurantIdAndRecipeSnapshotIdInOrderByRecipeSnapshotIdAscIngredientIdAsc(eq(tenant), anyCollection());
        sequence.verify(inventory).reserve(eq(actor), eq(orderId), sent.capture(), eq(true), eq(now), eq("ip"));
        var decisions = sent.getValue();
        assertThat(decisions).extracting(AcceptedItem::orderItemId).containsExactly(first.getId(), second.getId());
        assertThat(decisions.getFirst().recipeSnapshotId()).isEqualTo(firstSnapshot.getId());
        assertThat(decisions.getFirst().snapshotStatus()).isEqualTo("CAPTURED");
        assertThat(decisions.getFirst().orderQuantity()).isEqualByComparingTo("3");
        assertThat(decisions.getFirst().orderUnit()).isEqualTo("portion");
        assertThat(decisions.getFirst().usages()).singleElement().satisfies(value -> {
            assertThat(value.recipeUsageId()).isEqualTo(tracked.getId());
            assertThat(value.ingredientId()).isEqualTo(tracked.getIngredientId());
            assertThat(value.quantity()).isEqualByComparingTo("315.001");
            assertThat(value.sku()).isEqualTo("ING");
            assertThat(value.name()).isEqualTo("Ingredient");
            assertThat(value.unit()).isEqualTo("g");
        });
        assertThat(decisions.get(1).usages().getFirst().quantity()).isEqualByComparingTo("0.001");
        assertThatThrownBy(() -> decisions.add(decisions.getFirst())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> decisions.getFirst().usages().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(first.getSentToKitchenAt()).isNull();
        assertThat(first.getStatus()).isEqualTo(OrderItemStatus.PENDING);
    }

    @Test void missingSnapshotCannotBeConvertedIntoAnInventoryDecision() {
        var line = line();
        when(snapshots.findAllByRestaurantIdAndOrderItemIdIn(eq(tenant), anyCollection())).thenReturn(List.of());
        assertThatThrownBy(() -> operation.accept(order(), List.of(line), actor, now, "ip"))
                .isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.getCode()).isEqualTo("ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT"));
        verifyNoInteractions(inventory);
    }

    @Test void changedOrderQuantityIsRejectedBeforeInventoryReservation() {
        var line = line(); var recorded = snapshot(line); line.setQuantity(new BigDecimal("4"));
        when(snapshots.findAllByRestaurantIdAndOrderItemIdIn(eq(tenant), anyCollection())).thenReturn(List.of(recorded));
        assertThatThrownBy(() -> operation.accept(order(), List.of(line), actor, now, "ip"))
                .isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.getCode()).isEqualTo("ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT"));
        verifyNoInteractions(inventory);
    }

    @Test void validationPassesCurrentLineStatesWithoutCapturingOrMutatingInventory() {
        var submitted = line(); submitted.setSentToKitchenAt(now); submitted.setStatus(OrderItemStatus.SERVED);
        var draft = line(); draft.setStatus(OrderItemStatus.CANCELLED);
        operation.validate(order(), List.of(submitted, draft));
        verify(inventory).validate(tenant, orderId, List.of(
                new LineState(submitted.getId(), "SERVED", true, submitted.getQuantity(), "portion"),
                new LineState(draft.getId(), "CANCELLED", false, draft.getQuantity(), "portion")));
        verifyNoMoreInteractions(inventory);
        verifyNoInteractions(capture, snapshots, usages);
    }

    @Test void releaseBatchesSubmittedLinesAndNeverAllocatesDrafts() {
        var submitted = line(); submitted.setSentToKitchenAt(now);
        var draft = line();
        operation.release(order(), List.of(draft, submitted), actor, now, "ip");
        verify(inventory).release(actor, orderId, List.of(submitted.getId()), now, "ip");
        verifyNoMoreInteractions(inventory);
        verifyNoInteractions(capture, snapshots, usages);
    }

    @Test void emptyAcceptanceAndEmptyConsumptionOrDraftReleaseHaveNoSideEffects() {
        var root = order();
        operation.accept(root, List.of(), actor, now, "ip");
        operation.consume(root, List.of(), actor, now, "ip");
        operation.release(root, List.of(line()), actor, now, "ip");
        verifyNoInteractions(capture, snapshots, usages, inventory);
    }

    private Order order() { var value = new Order(); value.initialize(tenant, now); value.setId(orderId); return value; }
    private OrderItem line() {
        var value = new OrderItem(); value.initialize(tenant, now); value.setId(UUID.randomUUID());
        value.setOrderId(orderId); value.setItemId(UUID.randomUUID()); value.setUnit("portion");
        value.setQuantity(new BigDecimal("3.000")); return value;
    }
    private OrderItemRecipeSnapshot snapshot(OrderItem line) {
        var value = new OrderItemRecipeSnapshot(line, RecipeSnapshotStatus.CAPTURED, UUID.randomUUID(), 1,
                new BigDecimal("10.000"), "portion", now, true, OrderCostSnapshot.complete(BigDecimal.ZERO,BigDecimal.ZERO));
        ReflectionTestUtils.setField(value, "id", UUID.randomUUID()); return value;
    }
    private OrderItemRecipeUsage usage(OrderItemRecipeSnapshot snapshot, boolean tracked, String quantity) {
        var value = new OrderItemRecipeUsage(tenant, snapshot.getId(), UUID.randomUUID(), "Ingredient", "ING", "g",
                new BigDecimal("1000.000"), new BigDecimal("5.00"), tracked, new BigDecimal(quantity), now,
                new OrderIngredientCostSnapshot(true,BigDecimal.ZERO,BigDecimal.ZERO));
        ReflectionTestUtils.setField(value, "id", UUID.randomUUID()); return value;
    }
}
