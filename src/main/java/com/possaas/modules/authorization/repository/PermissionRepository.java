package com.possaas.modules.authorization.repository;

import com.possaas.modules.authorization.entity.Permission;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
    java.util.List<Permission> findByActiveTrueOrderByCodeAsc();
    java.util.List<Permission> findByCodeIn(java.util.Collection<String> codes);
}
