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
public class UserPermissionId implements Serializable {
    private UUID userId;
    private UUID permissionId;
}
