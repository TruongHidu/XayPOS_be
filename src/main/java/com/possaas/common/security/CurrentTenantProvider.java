package com.possaas.common.security;

import com.possaas.common.exception.BusinessException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentTenantProvider {
    private final CurrentUserProvider currentUserProvider;

    public UUID getRequiredRestaurantId() {
        UUID restaurantId = currentUserProvider.getRequired().restaurantId();
        if (restaurantId == null) {
            throw new BusinessException(
                HttpStatus.FORBIDDEN,
                "TENANT_ACCESS_DENIED",
                "A tenant context is required"
            );
        }
        return restaurantId;
    }
}
