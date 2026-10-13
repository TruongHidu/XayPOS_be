package com.possaas.modules.payment.dto;

import com.possaas.modules.payment.entity.PaymentConfirmationSource;
import com.possaas.modules.payment.entity.PaymentMethod;
import com.possaas.modules.payment.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID id, UUID orderId, String paymentCode, BigDecimal amount, String currencyCode,
    PaymentMethod paymentMethod, PaymentConfirmationSource confirmationSource, String provider,
    PaymentStatus status, String referenceNo, Instant requestedAt, Instant paidAt, UUID createdBy
) {}
