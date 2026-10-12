package com.possaas.modules.table.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Insert-only movement snapshots. The private replay material never leaves this module. */
@Entity
@Table(name = "table_session_transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TableSessionTransfer {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;
    @Column(name = "from_table_id", nullable = false, updatable = false)
    private UUID fromTableId;
    @Column(name = "to_table_id", nullable = false, updatable = false)
    private UUID toTableId;
    @Column(name = "from_table_code", nullable = false, updatable = false, length = 50)
    private String fromTableCode;
    @Column(name = "from_table_name", nullable = false, updatable = false, length = 100)
    private String fromTableName;
    @Column(name = "to_table_code", nullable = false, updatable = false, length = 50)
    private String toTableCode;
    @Column(name = "to_table_name", nullable = false, updatable = false, length = 100)
    private String toTableName;
    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;
    @Column(updatable = false, length = 1000)
    private String reason;
    @Column(name = "session_version_before", nullable = false, updatable = false)
    private long sessionVersionBefore;
    @Column(name = "session_version_after", nullable = false, updatable = false)
    private long sessionVersionAfter;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;
    @Column(name = "transferred_at", nullable = false, updatable = false)
    private Instant transferredAt;

    public TableSessionTransfer(TableSession session, RestaurantTable source, RestaurantTable target,
            UUID actor, String reason, long versionBefore, String key, String hash, Instant now) {
        restaurantId = session.getRestaurantId();
        sessionId = session.getId();
        fromTableId = source.getId(); fromTableCode = source.getCode(); fromTableName = source.getName();
        toTableId = target.getId(); toTableCode = target.getCode(); toTableName = target.getName();
        actorUserId = actor; this.reason = reason;
        sessionVersionBefore = versionBefore; sessionVersionAfter = session.getVersion();
        idempotencyKey = key; requestHash = hash; transferredAt = now;
    }
}
