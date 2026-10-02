package com.possaas.modules.user.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.authorization.entity.Permission;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TenantPermissionAssignmentPolicy {
    private static final Set<String> MODULES = Set.of("MENU", "TABLE", "ORDER", "KITCHEN", "PAYMENT", "STAFF", "INVENTORY", "REPORT", "AI");
    private static final Set<String> FORBIDDEN = Set.of("ADMIN_DASHBOARD_VIEW", "RESTAURANT_VIEW", "RESTAURANT_MANAGE",
        "AUDIT_VIEW", "PACKAGE_VIEW", "PACKAGE_MANAGE", "SUBSCRIPTION_VIEW", "SUBSCRIPTION_MANAGE");

    public boolean isAssignable(Permission p) { return p.isActive() && MODULES.contains(p.getModule()) && !FORBIDDEN.contains(p.getCode()); }

    public Set<String> normalize(Set<String> codes) {
        if (codes == null || codes.stream().anyMatch(c -> c == null || c.isBlank())) throw error("VALIDATION_ERROR");
        return codes.stream().map(c -> c.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new));
    }

    public void validate(Set<String> grants, Set<String> denies, List<Permission> permissions, Set<String> actorPermissions) {
        if (!Collections.disjoint(grants, denies)) throw error("PERMISSION_EFFECT_CONFLICT");
        Set<String> all = new HashSet<>(grants); all.addAll(denies);
        Map<String, Permission> byCode = permissions.stream().collect(Collectors.toMap(Permission::getCode, p -> p));
        for (String code : all) {
            Permission permission = byCode.get(code);
            if (permission == null || !permission.isActive()) throw error("PERMISSION_NOT_FOUND");
            if (!isAssignable(permission)) throw error("PERMISSION_NOT_ASSIGNABLE");
        }
        if (!actorPermissions.containsAll(grants))
            throw new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_ESCALATION_NOT_ALLOWED", "Cannot grant permissions the actor does not possess");
    }

    private static BusinessException error(String code) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, "Invalid staff permission assignment");
    }
}
