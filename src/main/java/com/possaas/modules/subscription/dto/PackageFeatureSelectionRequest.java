package com.possaas.modules.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Map;

public record PackageFeatureSelectionRequest(
    @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String code,
    Map<String, Object> limits
) {
    public PackageFeatureSelectionRequest {
        code = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
        limits = limits == null ? Map.of() : limits;
    }
}
