package com.possaas.modules.authorization.entity;

import com.possaas.common.persistence.BaseAuditable;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "roles")
public class Role extends BaseAuditable {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "restaurant_id") private UUID restaurantId;
    @Column(nullable = false, length = 50) private String code;
    @Column(nullable = false, length = 100) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "is_system", nullable = false) private boolean system;
    @Column(name = "is_active", nullable = false) private boolean active = true;
}
