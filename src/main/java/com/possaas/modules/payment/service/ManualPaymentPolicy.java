package com.possaas.modules.payment.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.payment.dto.RecordManualPaymentRequest;
import com.possaas.modules.payment.entity.PaymentMethod;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ManualPaymentPolicy {
    public ManualPayment normalize(RecordManualPaymentRequest request) {
        if (request.paymentMethod() == null || request.paymentMethod().isBlank())
            throw invalidMethod();
        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(request.paymentMethod().trim());
        } catch (IllegalArgumentException failure) {
            throw invalidMethod();
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_AMOUNT",
                "Payment amount must be positive with at most two decimal places");
        String reference = request.referenceNo() == null ? null : request.referenceNo().trim();
        if (reference != null && reference.length() > 100)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Reference number is too long");
        if (reference != null && reference.isEmpty()) reference = null;
        if (method == PaymentMethod.TRANSFER && reference == null)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TRANSFER_REFERENCE_REQUIRED",
                "Manual transfer requires a bank reference number");
        return new ManualPayment(method, amount.setScale(2), reference);
    }

    private BusinessException invalidMethod() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PAYMENT_METHOD",
            "Only CASH and manual TRANSFER are supported");
    }

    public record ManualPayment(PaymentMethod method, BigDecimal amount, String referenceNo) {}
}
