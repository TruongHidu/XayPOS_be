package com.possaas.modules.authorization.entity;

import com.possaas.common.persistence.BaseAuditable;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "permissions")
public class Permission extends BaseAuditable {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, unique = true, length = 100) private String code;
    @Column(nullable = false, length = 50) private String module;
    @Column(nullable = false, length = 150) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "is_active", nullable = false) private boolean active = true;
}
