package com.possaas.modules.subscription.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;

public record CreateSubscriptionRequest(
    @NotBlank String packageCode,
    @NotNull Instant startAt,
    @NotNull Instant endAt,
    boolean autoRenew,
    @NotNull @DecimalMin("0.0") BigDecimal priceAmount,
    @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode
) {}
