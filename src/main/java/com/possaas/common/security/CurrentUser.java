package com.possaas.common.security;

import java.util.Set;
import java.util.UUID;

public record CurrentUser(UUID userId, UUID restaurantId, String email, String role, Set<String> permissions) {
    public boolean isSuperAdmin() { return restaurantId == null && "SUPER_ADMIN".equals(role); }
    public boolean hasPermission(String permission) { return permissions != null && permissions.contains(permission); }
}
