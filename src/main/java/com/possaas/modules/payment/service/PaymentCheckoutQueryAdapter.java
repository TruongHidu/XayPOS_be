package com.possaas.modules.payment.service;

import com.possaas.modules.order.application.port.PaymentCheckoutQuery;
import com.possaas.modules.payment.entity.PaymentStatus;
import com.possaas.modules.payment.repository.PaymentRepository;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class PaymentCheckoutQueryAdapter implements PaymentCheckoutQuery {
    private final PaymentRepository payments;
    @Override public boolean hasPending(UUID tenant, Collection<UUID> ids) {
        return !ids.isEmpty() && payments.existsByRestaurantIdAndOrderIdInAndStatus(tenant, ids, PaymentStatus.PENDING);
    }
}
