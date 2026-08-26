package com.possaas.modules.subscription.dto;

import java.util.Map;

public record FeatureEntitlementResponse(String code, Map<String, Object> limits) {}
