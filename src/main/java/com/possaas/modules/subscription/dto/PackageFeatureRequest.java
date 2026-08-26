package com.possaas.modules.subscription.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record PackageFeatureRequest(
    @NotNull Map<String, Object> limits
) {}
