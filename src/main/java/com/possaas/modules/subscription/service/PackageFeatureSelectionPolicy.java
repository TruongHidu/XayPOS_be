package com.possaas.modules.subscription.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.subscription.dto.PackageFeatureSelectionRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PackageFeatureSelectionPolicy {
    public List<PackageFeatureSelectionRequest> validate(List<PackageFeatureSelectionRequest> requests) {
        if (requests == null) return List.of();
        var codes = new HashSet<String>();
        var result = new ArrayList<PackageFeatureSelectionRequest>();
        for (var request : requests) {
            if (request == null || request.code() == null || !request.code().matches("[A-Z][A-Z0-9_]{0,99}"))
                throw error("VALIDATION_ERROR", "Invalid feature code");
            if (!codes.add(request.code()))
                throw error("DUPLICATE_PACKAGE_FEATURE", "Feature appears more than once: " + request.code());
            result.add(new PackageFeatureSelectionRequest(request.code(), validateLimits(request.code(), request.limits())));
        }
        return List.copyOf(result);
    }

    public Map<String, Object> validateLimits(String code, Map<String, Object> limits) {
        Map<String, Object> result = new LinkedHashMap<>(limits == null ? Map.of() : limits);
        if (result.containsKey("maxStaff")) {
            throw error("INVALID_FEATURE_LIMIT", "maxStaff belongs at package level, not feature limits");
        }
        return result;
    }

    public boolean sameLimits(String code, Map<String, Object> existing, Map<String, Object> desired) {
        try {
            return validateLimits(code, existing).equals(desired);
        } catch (BusinessException invalidExistingConfiguration) {
            // A valid replacement is allowed to repair legacy malformed limits.
            return false;
        }
    }

    private static BusinessException error(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }
}
