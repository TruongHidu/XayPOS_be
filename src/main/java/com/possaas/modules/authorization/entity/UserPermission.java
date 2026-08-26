package com.possaas.modules.authorization.entity;

import com.possaas.common.persistence.BaseAuditable;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_permissions")
public class UserPermission extends BaseAuditable {
    @EmbeddedId private UserPermissionId id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private PermissionEffect effect;
    @Column(name = "created_by") private UUID createdBy;
}
