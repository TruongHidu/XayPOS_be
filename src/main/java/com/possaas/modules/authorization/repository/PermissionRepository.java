package com.possaas.modules.authorization.repository;

import com.possaas.modules.authorization.entity.Permission;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {}
