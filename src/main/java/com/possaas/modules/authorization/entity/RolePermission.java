package com.possaas.modules.authorization.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "role_permissions")
public class RolePermission {
    @EmbeddedId private RolePermissionId id;
    @Column(name = "granted_by") private UUID grantedBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    public RolePermission(RolePermissionId id) { this.id = id; }
}
