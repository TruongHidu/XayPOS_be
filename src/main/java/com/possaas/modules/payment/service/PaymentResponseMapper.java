package com.possaas.modules.payment.service;

import com.possaas.modules.payment.dto.PaymentResponse;
import com.possaas.modules.payment.entity.Payment;
import org.springframework.stereotype.Component;

@Component
public class PaymentResponseMapper {
    public PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getPaymentCode(),
            payment.getAmount().setScale(2), payment.getCurrencyCode(), payment.getPaymentMethod(),
            payment.getConfirmationSource(), payment.getProvider(), payment.getStatus(),
            payment.getReferenceNo(), payment.getRequestedAt(), payment.getPaidAt(), payment.getCreatedBy());
    }
}
