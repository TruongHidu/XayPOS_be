package com.possaas.modules.authorization.repository;

import com.possaas.modules.authorization.entity.RolePermission;
import com.possaas.modules.authorization.entity.RolePermissionId;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {
    @Query(value = """
        SELECT p.code FROM role_permissions rp
        JOIN permissions p ON p.id = rp.permission_id
        WHERE rp.role_id = :roleId AND p.is_active = true
        """, nativeQuery = true)
    Set<String> findActivePermissionCodes(@Param("roleId") UUID roleId);
}
