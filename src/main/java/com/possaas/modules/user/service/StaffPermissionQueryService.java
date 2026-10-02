package com.possaas.modules.user.service;

import com.possaas.modules.authorization.entity.PermissionEffect;
import com.possaas.modules.authorization.repository.*;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.user.dto.*;
import com.possaas.modules.user.entity.User;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffPermissionQueryService {
    private final StaffAccessService access;
    private final StaffManagementPolicy management;
    private final TenantPermissionAssignmentPolicy policy;
    private final PermissionRepository permissions;
    private final RolePermissionRepository rolePermissions;
    private final UserPermissionRepository overrides;
    private final PermissionService effective;

    public List<StaffPermissionCatalogResponse> catalog() {
        access.requireAccess(true, false);
        return permissions.findByActiveTrueOrderByCodeAsc().stream().filter(policy::isAssignable)
            .map(p -> new StaffPermissionCatalogResponse(p.getCode(), p.getModule(), p.getName(), p.getDescription())).toList();
    }

    public StaffPermissionsResponse get(UUID id) {
        var actor = access.requireAccess(true, false);
        User user = access.target(actor.restaurantId(), id, false);
        management.requireTarget(actor, id, access.role(user).getCode());
        return snapshot(user);
    }

    public StaffPermissionsResponse snapshot(User user) {
        var role = access.role(user);
        var rows = overrides.findActivePermissionEffects(user.getId());
        List<String> grants = new ArrayList<>(), denies = new ArrayList<>();
        for (Object[] row : rows) {
            (PermissionEffect.GRANT.name().equals(String.valueOf(row[1])) ? grants : denies).add(String.valueOf(row[0]));
        }
        Collections.sort(grants); Collections.sort(denies);
        return new StaffPermissionsResponse(user.getId(), role.getCode(), rolePermissions.findActivePermissionCodes(role.getId()).stream().sorted().toList(),
            grants, denies, effective.getEffectivePermissionCodes(user).stream().sorted().toList());
    }
}
