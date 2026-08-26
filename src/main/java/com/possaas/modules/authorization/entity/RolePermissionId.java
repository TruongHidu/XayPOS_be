package com.possaas.modules.authorization.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
@Embeddable
public class RolePermissionId implements Serializable {
    private UUID roleId;
    private UUID permissionId;
}
