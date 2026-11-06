package com.possaas.modules.user.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.subscription.domain.CurrentEntitlement;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class StaffCapacityPolicy {
    public void requireCapacity(CurrentEntitlement entitlement, long activeStaff) {
        entitlement.features().stream().filter(f -> "STAFF_MANAGEMENT".equals(f.code())).findFirst()
            .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "FEATURE_NOT_ENTITLED", "Staff management is not enabled"));
        Long limit = entitlement.maxStaff();
        if (limit == null) return;
        if (activeStaff >= limit) throw new BusinessException(HttpStatus.CONFLICT, "STAFF_LIMIT_REACHED", "Active staff limit reached");
    }
}
