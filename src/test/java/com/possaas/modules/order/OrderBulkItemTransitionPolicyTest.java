package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.OrderItem;
import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.modules.order.service.OrderBulkItemTransitionPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

class OrderBulkItemTransitionPolicyTest {
    private final OrderBulkItemTransitionPolicy policy = new OrderBulkItemTransitionPolicy();

    @Test
    void readySelectsOnlyCookingAndNeverMutatesInputs() {
        var cooking = line(OrderItemStatus.COOKING);
        var ready = line(OrderItemStatus.READY);
        var served = line(OrderItemStatus.SERVED);
        var cancelled = line(OrderItemStatus.CANCELLED);

        assertThat(policy.readyItems(List.of(cooking, ready, served, cancelled))).containsExactly(cooking);
        assertThat(cooking.getStatus()).isEqualTo(OrderItemStatus.COOKING);
        assertThat(served.getStatus()).isEqualTo(OrderItemStatus.SERVED);
        assertThat(cancelled.getStatus()).isEqualTo(OrderItemStatus.CANCELLED);
    }

    @Test
    void readyRejectsPendingEvenAfterAnEligibleCookingLine() {
        var cooking = line(OrderItemStatus.COOKING);
        assertConflict(() -> policy.readyItems(List.of(cooking, line(OrderItemStatus.PENDING))),
            "INVALID_ORDER_ITEM_TRANSITION");
        assertThat(cooking.getStatus()).isEqualTo(OrderItemStatus.COOKING);
    }

    @Test
    void servedSelectsPendingCookingAndReadyAndNeverMutatesInputs() {
        var pending = line(OrderItemStatus.PENDING);
        var cooking = line(OrderItemStatus.COOKING);
        var ready = line(OrderItemStatus.READY);
        var served = line(OrderItemStatus.SERVED);
        var cancelled = line(OrderItemStatus.CANCELLED);

        assertThat(policy.servedItems(List.of(pending, cooking, ready, served, cancelled)))
            .containsExactly(pending, cooking, ready);
        assertThat(pending.getStatus()).isEqualTo(OrderItemStatus.PENDING);
        assertThat(cooking.getStatus()).isEqualTo(OrderItemStatus.COOKING);
        assertThat(ready.getStatus()).isEqualTo(OrderItemStatus.READY);
        assertThat(served.getStatus()).isEqualTo(OrderItemStatus.SERVED);
        assertThat(cancelled.getStatus()).isEqualTo(OrderItemStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemStatus.class, names = {"PENDING", "COOKING", "READY"})
    void servedAllowsEachUnservedLiveStatus(OrderItemStatus status) {
        var eligible = line(status);
        assertThat(policy.servedItems(List.of(eligible))).containsExactly(eligible);
        assertThat(eligible.getStatus()).isEqualTo(status);
    }

    @Test
    void alreadyReadyOrServedNeedsNoReadyChanges() {
        assertThat(policy.readyItems(List.of(line(OrderItemStatus.READY), line(OrderItemStatus.SERVED),
            line(OrderItemStatus.CANCELLED)))).isEmpty();
        assertThat(policy.readyItems(List.of(line(OrderItemStatus.SERVED)))).isEmpty();
    }

    @Test
    void alreadyServedNeedsNoServingChanges() {
        assertThat(policy.servedItems(List.of(line(OrderItemStatus.SERVED),
            line(OrderItemStatus.CANCELLED)))).isEmpty();
    }

    @Test
    void readyRejectsEmptyAndCancelledOnlyOrders() {
        assertConflict(() -> policy.readyItems(List.of()), "EMPTY_ORDER");
        assertConflict(() -> policy.readyItems(List.of(line(OrderItemStatus.CANCELLED))), "EMPTY_ORDER");
    }

    @Test
    void servedRejectsEmptyAndCancelledOnlyOrders() {
        assertConflict(() -> policy.servedItems(List.of()), "EMPTY_ORDER");
        assertConflict(() -> policy.servedItems(List.of(line(OrderItemStatus.CANCELLED))), "EMPTY_ORDER");
    }

    private OrderItem line(OrderItemStatus status) {
        var line = new OrderItem();
        line.setStatus(status);
        return line;
    }

    private void assertConflict(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, failure -> {
            assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(failure.getCode()).isEqualTo(code);
        });
    }
}
