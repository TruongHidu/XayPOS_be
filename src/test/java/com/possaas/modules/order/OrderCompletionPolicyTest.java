package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderItem;
import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.service.OrderCompletionPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderCompletionPolicyTest {
    private final OrderCompletionPolicy policy = new OrderCompletionPolicy();

    @Test
    void moneyScaleDoesNotAffectPaidInFullAndCancelledItemsAreIgnored() {
        var order = paidOrder(); order.setTotalAmount(new BigDecimal("10.00")); order.setPaidAmount(new BigDecimal("10.0"));
        assertThatCode(() -> policy.requireCompletable(order, List.of(line(OrderItemStatus.SERVED), line(OrderItemStatus.CANCELLED))))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"UNPAID,0,10", "PARTIALLY_PAID,5,10", "PAID,9.99,10", "PAID,10.01,10", "PAID,0,0", "REFUNDED,10,10"})
    void positiveFullyPaidBalanceIsRequired(String status, String paid, String total) {
        var order = paidOrder(); order.setPaymentStatus(OrderPaymentStatus.valueOf(status));
        order.setPaidAmount(new BigDecimal(paid)); order.setTotalAmount(new BigDecimal(total));
        assertError("ORDER_NOT_PAID", () -> policy.requireCompletable(order, List.of(line(OrderItemStatus.SERVED))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "COOKING", "READY"})
    void anyUnservedEffectiveItemBlocksCompletion(String status) {
        assertError("ORDER_NOT_READY_FOR_COMPLETION", () -> policy.requireCompletable(paidOrder(),
            List.of(line(OrderItemStatus.SERVED), line(OrderItemStatus.valueOf(status)))));
    }

    @Test
    void emptyOrCancelledOnlyCollectionIsNotACompletedMeal() {
        assertError("ORDER_NOT_READY_FOR_COMPLETION", () -> policy.requireCompletable(paidOrder(), List.of()));
        assertError("ORDER_NOT_READY_FOR_COMPLETION", () -> policy.requireCompletable(paidOrder(), List.of(line(OrderItemStatus.CANCELLED))));
    }

    @Test
    void legacyCompletedOrderCanBeRevalidatedForSessionRepair() {
        var order = paidOrder(); order.setStatus(OrderStatus.COMPLETED);
        assertThatCode(() -> policy.requireCompletable(order, List.of(line(OrderItemStatus.SERVED)))).doesNotThrowAnyException();
        order.setPaidAmount(BigDecimal.ZERO);
        assertError("ORDER_NOT_PAID", () -> policy.requireCompletable(order, List.of(line(OrderItemStatus.SERVED))));
    }

    @Test
    void sessionIdIsRequiredAndUnfinishedOrderRequiresAnOpenSession() {
        var order = paidOrder();
        assertError("INVALID_ORDER_SEATING", () -> policy.requireTableSession(order));
        order.setTableSessionId(UUID.randomUUID()); policy.requireTableSession(order);
        assertError("TABLE_SESSION_NOT_OPEN", () -> policy.requireOpenSession(true));
        assertThatCode(() -> policy.requireOpenSession(false)).doesNotThrowAnyException();
    }

    private Order paidOrder() {
        var order = new Order(); order.setStatus(OrderStatus.SERVED); order.setPaymentStatus(OrderPaymentStatus.PAID);
        order.setPaidAmount(BigDecimal.TEN); order.setTotalAmount(BigDecimal.TEN); return order;
    }
    private OrderItem line(OrderItemStatus status) { var line = new OrderItem(); line.setStatus(status); return line; }
    private void assertError(String code, Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(code));
    }
}
