package com.possaas.modules.subscription.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record AdminPackageResponse(
    UUID id,
    String code,
    String name,
    String description,
    BigDecimal priceAmount,
    String currencyCode,
    short billingCycleMonths,
    boolean active,
    List<FeatureEntitlementResponse> features
) {}
