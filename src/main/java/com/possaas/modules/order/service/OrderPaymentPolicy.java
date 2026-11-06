package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import java.math.BigDecimal;
import java.util.EnumSet;
import org.springframework.stereotype.Component;

@Component
public class OrderPaymentPolicy {
    private static final EnumSet<OrderStatus> PAYABLE = EnumSet.of(
        OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.SERVED);

    public void requireFullSettlement(Order order, long expectedVersion, BigDecimal amount) {
        if (order.getPaymentStatus() == OrderPaymentStatus.PAID)
            throw new ConflictException("ORDER_ALREADY_PAID", "Order is already paid");
        if (!PAYABLE.contains(order.getStatus())
                || order.getPaymentStatus() != OrderPaymentStatus.UNPAID
                || order.getPaidAmount().signum() != 0
                || order.getTotalAmount().signum() <= 0)
            throw new ConflictException("ORDER_NOT_PAYABLE", "Order cannot be paid in its current state");
        if (order.getVersion() != expectedVersion)
            throw new ConflictException("CONCURRENT_PAYMENT_UPDATE", "Order changed; reload before paying");
        if (amount.compareTo(order.getTotalAmount()) != 0)
            throw new ConflictException("PAYMENT_AMOUNT_MISMATCH", "Payment amount differs from the current bill");
    }
}
