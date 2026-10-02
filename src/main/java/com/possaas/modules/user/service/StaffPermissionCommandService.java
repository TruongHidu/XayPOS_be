package com.possaas.modules.user.service;

import com.possaas.modules.authorization.entity.*;
import com.possaas.modules.authorization.repository.*;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.user.application.port.UserSessionRevoker;
import com.possaas.modules.user.dto.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StaffPermissionCommandService {
    private final StaffAccessService access;
    private final StaffManagementPolicy management;
    private final TenantPermissionAssignmentPolicy policy;
    private final PermissionRepository permissions;
    private final UserPermissionRepository overrides;
    private final PermissionService effective;
    private final StaffPermissionQueryService query;
    private final UserSessionRevoker sessions;
    private final AuditService audit;

    @Transactional
    public StaffPermissionsResponse replace(UUID id, ReplaceStaffPermissionsRequest request, String ip) {
        var actor = access.requireAccess(true, true);
        var user = access.target(actor.restaurantId(), id, true);
        management.requireTarget(actor, id, access.role(user).getCode());
        Set<String> grants = policy.normalize(request.grants()), denies = policy.normalize(request.denies());
        Set<String> all = new HashSet<>(grants); all.addAll(denies);
        var selected = permissions.findByCodeIn(all);
        // Current DB permissions prevent a stale JWT from granting removed authority.
        var actorUser = access.target(actor.restaurantId(), actor.userId(), false);
        policy.validate(grants, denies, selected, effective.getEffectivePermissionCodes(actorUser));
        var existing = overrides.findByIdUserId(id);
        Map<UUID,PermissionEffect> desired = new HashMap<>();
        selected.forEach(p -> desired.put(p.getId(), grants.contains(p.getCode()) ? PermissionEffect.GRANT : PermissionEffect.DENY));
        Map<UUID,PermissionEffect> current = new HashMap<>();
        existing.forEach(p -> current.put(p.getId().getPermissionId(), p.getEffect()));
        if (current.equals(desired)) return query.snapshot(user);
        var before = query.snapshot(user);
        overrides.deleteAll(existing);
        overrides.flush();
        var replacements = selected.stream().map(p -> {
            UserPermission override = new UserPermission(); UserPermissionId key = new UserPermissionId();
            key.setUserId(id); key.setPermissionId(p.getId()); override.setId(key);
            override.setEffect(desired.get(p.getId())); override.setCreatedBy(actor.userId()); return override;
        }).toList();
        overrides.saveAllAndFlush(replacements);
        sessions.revokeAllActiveForUser(id);
        audit.record(actor.restaurantId(), actor.userId(), "STAFF_PERMISSIONS_UPDATED", "users", id,
            Map.of("grants", before.grants(), "denies", before.denies()), Map.of("grants", grants, "denies", denies), ip);
        return query.snapshot(user);
    }
}
