package com.possaas.modules.subscription.dto;

import java.util.UUID;

public record FeatureResponse(
    UUID id,
    String code,
    String name,
    String description,
    boolean active
) {}
