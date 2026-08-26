package com.possaas.modules.audit.entity;

import jakarta.persistence.*;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Immutable
@Entity
@Table(name = "audit_logs")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "restaurant_id") private UUID restaurantId;
    @Column(name = "actor_user_id") private UUID actorUserId;
    @Column(name = "action_code", nullable = false, length = 100) private String actionCode;
    @Column(name = "entity_type", nullable = false, length = 80) private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "before_data", columnDefinition = "jsonb") private Map<String, Object> beforeData;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "after_data", columnDefinition = "jsonb") private Map<String, Object> afterData;
    @JdbcTypeCode(SqlTypes.INET) @Column(name = "ip_address", columnDefinition = "inet") private InetAddress ipAddress;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
