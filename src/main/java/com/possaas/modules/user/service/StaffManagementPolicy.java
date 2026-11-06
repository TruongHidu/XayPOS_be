package com.possaas.modules.user.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class StaffManagementPolicy {
    private static final Set<String> OWNER_ROLES = Set.of("MANAGER", "WAITER", "KITCHEN", "CASHIER");
    private static final Set<String> MANAGER_ROLES = Set.of("WAITER", "KITCHEN", "CASHIER");

    public Set<String> assignableRoles(CurrentUser actor) {
        if (actor.restaurantId() == null) throw denied("TENANT_ACCESS_DENIED");
        return switch (actor.role()) {
            case "OWNER" -> OWNER_ROLES;
            case "MANAGER" -> MANAGER_ROLES;
            default -> throw denied("STAFF_TARGET_FORBIDDEN");
        };
    }

    public void requireAssignable(CurrentUser actor, String role) {
        if (!assignableRoles(actor).contains(role))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STAFF_ROLE_NOT_ALLOWED", "Role cannot be assigned by this actor");
    }

    public void requireTarget(CurrentUser actor, UUID userId, String targetRole) {
        var allowed = assignableRoles(actor);
        if ("OWNER".equals(targetRole)) throw denied("OWNER_ACCOUNT_PROTECTED");
        if (actor.userId().equals(userId)) throw denied("STAFF_SELF_MANAGEMENT_NOT_ALLOWED");
        if (!allowed.contains(targetRole)) throw denied("STAFF_TARGET_FORBIDDEN");
    }

    private static BusinessException denied(String code) {
        return new BusinessException(HttpStatus.FORBIDDEN, code, "Staff management is not permitted");
    }
}
