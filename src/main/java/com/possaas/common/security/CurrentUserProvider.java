package com.possaas.common.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {
    public CurrentUser getRequired() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
            throw new IllegalStateException("Authenticated user is required");
        }
        return current;
    }

    public UUID restaurantId() {
        return getRequired().restaurantId();
    }
}
