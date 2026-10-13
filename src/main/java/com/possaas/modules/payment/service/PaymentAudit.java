package com.possaas.modules.payment.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.payment.entity.Payment;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class PaymentAudit {
    private final AuditService audit;

    public void recorded(Payment payment, String ip) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", payment.getOrderId());
        after.put("paymentCode", payment.getPaymentCode());
        after.put("status", payment.getStatus());
        after.put("paymentMethod", payment.getPaymentMethod());
        after.put("confirmationSource", payment.getConfirmationSource());
        after.put("amount", payment.getAmount());
        after.put("currencyCode", payment.getCurrencyCode());
        after.put("paidAt", payment.getPaidAt().toString());
        audit.record(payment.getRestaurantId(), payment.getCreatedBy(), "PAYMENT_RECORDED", "payments",
            payment.getId(), Map.of(), after, ip);
    }
}
