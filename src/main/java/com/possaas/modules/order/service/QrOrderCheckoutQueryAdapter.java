package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.application.port.QrOrderCheckoutQuery;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.order.application.port.PaymentCheckoutQuery;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class QrOrderCheckoutQueryAdapter implements QrOrderCheckoutQuery {
    private final OrderRepository orders;
    private final PaymentCheckoutQuery payments;
    @Override public boolean isAccepting(UUID tenant, UUID session) {
        var attached = orders.findAllByRestaurantIdAndTableSessionId(tenant, session);
        return attached.stream().noneMatch(o -> o.getPaidAmount().signum() != 0
                || o.getPaymentStatus() != OrderPaymentStatus.UNPAID)
            && !payments.hasPending(tenant, attached.stream().map(Order::getId).toList());
    }
    @Override public void requireAccepting(UUID tenant, UUID session) {
        if (!isAccepting(tenant, session))
            throw new ConflictException("QR_ORDER_CHECKOUT_IN_PROGRESS", "Session has a checkout or financial obligation");
    }
}
