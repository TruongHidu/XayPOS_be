package com.possaas.modules.authorization.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.authorization.entity.PermissionEffect;
import com.possaas.modules.authorization.entity.Role;
import com.possaas.modules.authorization.repository.RolePermissionRepository;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.repository.UserPermissionRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PermissionService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserPermissionRepository userPermissionRepository;

    @Transactional(readOnly = true)
    public Set<String> getEffectivePermissionCodes(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found"));
        return getEffectivePermissionCodes(user);
    }

    @Transactional(readOnly = true)
    public Set<String> getEffectivePermissionCodes(User user) {
        Role role = roleRepository.findById(user.getRoleId())
            .orElseThrow(() -> new ResourceNotFoundException("ROLE_NOT_FOUND", "User role not found"));
        Set<String> effectivePermissions = new HashSet<>(
            rolePermissionRepository.findActivePermissionCodes(role.getId())
        );
        Set<String> deniedPermissions = new HashSet<>();

        for (Object[] row : userPermissionRepository.findActivePermissionEffects(user.getId())) {
            String permissionCode = String.valueOf(row[0]);
            String effect = String.valueOf(row[1]);
            if (PermissionEffect.GRANT.name().equals(effect)) {
                effectivePermissions.add(permissionCode);
            } else if (PermissionEffect.DENY.name().equals(effect)) {
                deniedPermissions.add(permissionCode);
            }
        }

        effectivePermissions.removeAll(deniedPermissions);
        return Set.copyOf(effectivePermissions);
    }

    @Transactional(readOnly = true)
    public boolean hasPermission(UUID userId, String permissionCode) {
        return getEffectivePermissionCodes(userId).contains(permissionCode);
    }
}
