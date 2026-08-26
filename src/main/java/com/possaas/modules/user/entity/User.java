package com.possaas.modules.user.entity;

import com.possaas.common.persistence.BaseAuditable;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User extends BaseAuditable {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "restaurant_id") private UUID restaurantId;
    @Column(name = "role_id", nullable = false) private UUID roleId;
    @Column(nullable = false, length = 150) private String name;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Column(length = 30) private String phone;
    @Column(name = "password_hash", nullable = false, length = 255) private String passwordHash;
    @Column(name = "is_active", nullable = false) private boolean active = true;
    @Column(name = "last_login_at") private Instant lastLoginAt;
    @Column(name = "deleted_at") private Instant deletedAt;
}
