package com.possaas.modules.payment.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.order.application.port.OrderPaymentCommand;
import com.possaas.modules.payment.dto.PaymentResponse;
import com.possaas.modules.payment.repository.PaymentRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class PaymentQueryService {
    private final PaymentAccessService access;
    private final OrderPaymentCommand orderPayments;
    private final PaymentRepository payments;
    private final PaymentResponseMapper mapper;

    public PageResponse<PaymentResponse> list(UUID orderId, int page, int size) {
        UUID tenant = access.read().restaurantId();
        orderPayments.requireOwned(tenant, orderId);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.from(payments.findAllByRestaurantIdAndOrderId(tenant, orderId, pageable)
            .map(mapper::toResponse));
    }

    public PaymentResponse detail(UUID orderId, UUID paymentId) {
        UUID tenant = access.read().restaurantId();
        orderPayments.requireOwned(tenant, orderId);
        return payments.findByIdAndRestaurantIdAndOrderId(paymentId, tenant, orderId)
            .map(mapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("PAYMENT_NOT_FOUND", "Payment not found"));
    }
}
