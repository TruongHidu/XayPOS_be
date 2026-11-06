package com.possaas.modules.report.dto;

import com.possaas.modules.payment.entity.PaymentMethod;
import java.math.BigDecimal;

public record PaymentMethodBreakdownResponse(
    PaymentMethod paymentMethod,
    BigDecimal collectedAmount,
    long successfulPaymentCount,
    BigDecimal percentage
) {}
