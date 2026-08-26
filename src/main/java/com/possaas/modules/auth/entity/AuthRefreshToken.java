package com.possaas.modules.auth.entity;

import jakarta.persistence.*;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "auth_refresh_tokens")
public class AuthRefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 255) private String tokenHash;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "device_info", length = 255) private String deviceInfo;
    @JdbcTypeCode(SqlTypes.INET) @Column(name = "ip_address", columnDefinition = "inet") private InetAddress ipAddress;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
