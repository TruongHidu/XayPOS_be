package com.possaas.modules.authorization.repository;

import com.possaas.modules.authorization.entity.UserPermission;
import com.possaas.modules.authorization.entity.UserPermissionId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPermissionRepository extends JpaRepository<UserPermission, UserPermissionId> {
    @Query(value = """
        SELECT p.code, up.effect FROM user_permissions up
        JOIN permissions p ON p.id = up.permission_id
        WHERE up.user_id = :userId AND p.is_active = true
        """, nativeQuery = true)
    List<Object[]> findActivePermissionEffects(@Param("userId") UUID userId);
}
