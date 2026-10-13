package com.possaas.modules.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.service.OrderPaymentPolicy;
import com.possaas.modules.payment.dto.RecordManualPaymentRequest;
import com.possaas.modules.payment.service.ManualPaymentPolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentPolicyTest {
    private final OrderPaymentPolicy orders = new OrderPaymentPolicy();
    private final ManualPaymentPolicy manual = new ManualPaymentPolicy();

    @Test void confirmedPreparingReadyAndServedArePayableWithoutServingAllItems() {
        for (OrderStatus status : new OrderStatus[] {OrderStatus.CONFIRMED, OrderStatus.PREPARING,
            OrderStatus.READY, OrderStatus.SERVED}) {
            Order order = order(status);
            assertThatCode(() -> orders.requireFullSettlement(order, 0, new BigDecimal("100.00")))
                .doesNotThrowAnyException();
        }
    }

    @Test void rejectsInvalidOrderStateVersionAndAmount() {
        for (OrderStatus status : new OrderStatus[] {OrderStatus.OPEN, OrderStatus.CANCELLED,
            OrderStatus.COMPLETED}) {
            assertThatThrownBy(() -> orders.requireFullSettlement(order(status), 0, new BigDecimal("100")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("cannot be paid");
        }
        assertThatThrownBy(() -> orders.requireFullSettlement(order(OrderStatus.CONFIRMED), 1,
            new BigDecimal("100"))).isInstanceOf(BusinessException.class).hasMessageContaining("reload");
        assertThatThrownBy(() -> orders.requireFullSettlement(order(OrderStatus.CONFIRMED), 0,
            new BigDecimal("99"))).isInstanceOf(BusinessException.class).hasMessageContaining("differs");
        Order paid = order(OrderStatus.CONFIRMED);
        paid.setPaymentStatus(OrderPaymentStatus.PAID);
        assertThatThrownBy(() -> orders.requireFullSettlement(paid, 0, new BigDecimal("100")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("already paid");
    }

    @Test void manualTransferNeedsReferenceAndAmountsAreNotRounded() {
        assertThatThrownBy(() -> manual.normalize(new RecordManualPaymentRequest(0L,"TRANSFER",
            new BigDecimal("100"),null))).isInstanceOf(BusinessException.class)
            .hasMessageContaining("reference");
        assertThatThrownBy(() -> manual.normalize(new RecordManualPaymentRequest(0L,"CASH",
            new BigDecimal("1.001"),null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> manual.normalize(new RecordManualPaymentRequest(0L,"CASH",
            new BigDecimal("-1"),null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> manual.normalize(new RecordManualPaymentRequest(0L,"EWALLET",
            new BigDecimal("100"),null))).isInstanceOf(BusinessException.class);
    }

    private Order order(OrderStatus status) {
        Order order = new Order();
        order.setStatus(status);
        order.setPaymentStatus(OrderPaymentStatus.UNPAID);
        order.setPaidAmount(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("100.00"));
        return order;
    }
}
