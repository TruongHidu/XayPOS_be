package com.possaas.modules.table.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "table_sessions")
public class TableSession extends TableRecord {
    @Column(name = "table_id", nullable = false, updatable = false)
    private UUID tableId;
    @Column(name = "session_code", nullable = false, length = 50, updatable = false)
    private String sessionCode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TableSessionStatus status = TableSessionStatus.OPEN;
    @Column(name = "guest_count", nullable = false)
    private short guestCount = 1;
    @Column(name = "opened_by", nullable = false, updatable = false)
    private UUID openedBy;
    @Column(name = "closed_by")
    private UUID closedBy;
    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    private String note;
    @Column(name = "cancel_reason")
    private String cancelReason;
}
