package com.possaas.modules.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record RecordManualPaymentRequest(
    @NotNull @Min(0) Long expectedOrderVersion,
    String paymentMethod,
    BigDecimal amount,
    String referenceNo
) {}
