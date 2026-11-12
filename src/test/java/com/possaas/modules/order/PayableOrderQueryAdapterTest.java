package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.order.service.OrderPaymentPolicy;
import com.possaas.modules.order.service.PayableOrderQueryAdapter;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayableOrderQueryAdapterTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PayableOrderQueryAdapter adapter = new PayableOrderQueryAdapter(orders, new OrderPaymentPolicy());

    @Test void exposesWhatACheckoutNeeds() {
        Order order = order(OrderStatus.SERVED, OrderPaymentStatus.UNPAID);
        when(orders.findByIdAndRestaurantId(orderId, tenant)).thenReturn(Optional.of(order));
        var payable = adapter.requirePayable(tenant, orderId);
        assertThat(payable.orderCode()).isEqualTo("OD-ABC");
        assertThat(payable.totalAmount()).isEqualByComparingTo("120000");
        assertThat(payable.currencyCode()).isEqualTo("VND");
    }

    @Test void usesTheSamePayableRuleAsSettlement() {
        when(orders.findByIdAndRestaurantId(orderId, tenant))
            .thenReturn(Optional.of(order(OrderStatus.OPEN, OrderPaymentStatus.UNPAID)));
        assertThatThrownBy(() -> adapter.requirePayable(tenant, orderId))
            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("ORDER_NOT_PAYABLE");
        when(orders.findByIdAndRestaurantId(orderId, tenant))
            .thenReturn(Optional.of(order(OrderStatus.SERVED, OrderPaymentStatus.PAID)));
        assertThatThrownBy(() -> adapter.requirePayable(tenant, orderId))
            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("ORDER_ALREADY_PAID");
    }

    @Test void anotherRestaurantsOrderIsNotFound() {
        when(orders.findByIdAndRestaurantId(orderId, tenant)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adapter.requirePayable(tenant, orderId))
            .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("ORDER_NOT_FOUND");
    }

    private Order order(OrderStatus status, OrderPaymentStatus payment) {
        Order order = new Order();
        order.setId(orderId);
        order.setOrderCode("OD-ABC");
        order.setStatus(status);
        order.setPaymentStatus(payment);
        order.setPaidAmount(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("120000.00"));
        order.setCurrencyCode("VND");
        return order;
    }
}
