package com.possaas.modules.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.payment.entity.Payment;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class PaymentIdempotencyService {
    private final ObjectMapper mapper;

    public String key(String key) {
        if (key == null || key.isBlank() || key.length() > 100)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key is required and must be at most 100 characters");
        return key;
    }

    public String fingerprint(UUID actorId, UUID orderId, long expectedVersion,
                              ManualPaymentPolicy.ManualPayment payment) {
        try {
            var request = new CanonicalRequest("manual-full-payment-v1", actorId, orderId,
                expectedVersion, payment.method().name(), payment.amount().toPlainString(),
                payment.referenceNo());
            byte[] bytes = mapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.io.IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Could not fingerprint payment request", failure);
        }
    }

    public void requireReplayMatch(Payment previous, UUID actorId, String fingerprint) {
        if (!actorId.equals(previous.getCreatedBy()) || !fingerprint.equals(previous.getRequestFingerprint()))
            throw new ConflictException("PAYMENT_IDEMPOTENCY_KEY_REUSED",
                "Idempotency key belongs to a different payment request or actor");
    }

    private record CanonicalRequest(String operation, UUID actorId, UUID orderId,
                                    long expectedVersion, String method, String amount, String referenceNo) {}
}
