package com.possaas.modules.qrorder.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name="qr_guest_authorizations")
public class QrGuestAuthorization extends QrRecord {
    @Column(name="guest_id",nullable=false,updatable=false) private UUID guestId;
    @Enumerated(EnumType.STRING) @Column(name="entry_type",nullable=false,updatable=false,length=20) private QrEntryType entryType;
    @Column(name="table_id",updatable=false) private UUID tableId;
    @Column(name="table_access_epoch",updatable=false) private Long tableAccessEpoch;
    @Column(name="credential_hash",nullable=false,updatable=false,length=64) private String credentialHash;
    @Column(name="token_hash",nullable=false,updatable=false,length=64) private String tokenHash;
    @Column(name="expires_at",nullable=false,updatable=false) private Instant expiresAt;
    @Column(name="revoked_at") private Instant revokedAt;
    @Override public String toString() { return "QrGuestAuthorization[redacted]"; }
}
