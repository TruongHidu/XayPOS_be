package com.possaas.modules.subscription.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.subscription.domain.FeatureLimitValues;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PackageLimitPolicy {
    public Long validateMaxStaff(Object value) {
        if (value == null) return null;
        try {
            return FeatureLimitValues.positiveLong(value);
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PACKAGE_LIMIT",
                "maxStaff must be null or a positive integer at package level");
        }
    }
}
