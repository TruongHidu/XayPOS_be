package com.possaas.modules.qrorder.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.qrorder.application.port.QrRequestLifecycle;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.repository.*;
import com.possaas.modules.table.repository.RestaurantTableRepository;
import com.possaas.modules.table.repository.TableSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Only persistence and cancellation: deliberately has no dependency on QR approval or TABLE writers. */
@Service @RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class QrLifecycleService implements QrRequestLifecycle {
    private final QrTableAccessRepository access;
    private final QrGuestAuthorizationRepository grants;
    private final QrOrderRequestRepository requests;
    private final RestaurantTableRepository tables;
    private final TableSessionRepository sessions;
    private final AuditService audit;

    @Override public void invalidateTable(UUID tenant, UUID table, String reason, UUID actor, Instant now, String ip) {
        var scope = access.findByTableIdAndRestaurantId(table, tenant).orElseGet(() -> {
            var row = new QrTableAccess(); row.setTableId(table); row.setRestaurantId(tenant); return row;
        });
        scope.setEpoch(Math.addExact(scope.getEpoch(), 1)); scope.setUpdatedAt(now); access.saveAndFlush(scope);
        var authorizations = grants.findAllByRestaurantIdAndTableIdAndRevokedAtIsNull(tenant, table);
        authorizations.forEach(g -> { g.setRevokedAt(now); g.setUpdatedAt(now); }); grants.saveAll(authorizations);
        cancel(requests.findAllByRestaurantIdAndTableIdAndStatus(tenant, table, QrRequestStatus.PENDING), reason, actor, now, ip);
    }

    @Override public void invalidateRestaurantCredential(UUID tenant, String previousToken, UUID actor, Instant now, String ip) {
        String hash = digest(previousToken);
        var authorizations = grants.findAllByRestaurantIdAndEntryTypeAndCredentialHashAndRevokedAtIsNull(tenant, QrEntryType.RESTAURANT, hash);
        authorizations.forEach(g -> { g.setRevokedAt(now); g.setUpdatedAt(now); }); grants.saveAll(authorizations);
        cancel(requests.findAllByRestaurantIdAndEntryTypeAndCredentialHashAndStatus(tenant, QrEntryType.RESTAURANT, hash,
            QrRequestStatus.PENDING), "RESTAURANT_MENU_TOKEN_ROTATED", actor, now, ip);
    }

    @Override public void checkoutStarted(UUID tenant, UUID sessionId, UUID actor, Instant now, String ip) {
        var session = sessions.findByIdAndRestaurantId(sessionId, tenant).orElseThrow();
        var table = tables.findByIdAndRestaurantId(session.getTableId(), tenant).orElseThrow();
        long epoch = access.findByTableIdAndRestaurantId(table.getId(), tenant).map(QrTableAccess::getEpoch).orElse(0L);
        String credential = digest(table.getQrToken());
        var pending = requests.findAllByRestaurantIdAndObservedSessionIdAndStatus(tenant, sessionId, QrRequestStatus.PENDING)
            .stream().filter(r -> r.getEntryType() == QrEntryType.TABLE && table.getId().equals(r.getTableId())
                && Objects.equals(epoch, r.getTableAccessEpoch()) && credential.equals(r.getCredentialHash())).toList();
        cancel(pending, "CHECKOUT_STARTED", actor, now, ip);
    }

    private void cancel(List<QrOrderRequest> pending, String reason, UUID actor, Instant now, String ip) {
        for (var request : pending) {
            if (request.getStatus() != QrRequestStatus.PENDING) continue;
            request.setStatus(QrRequestStatus.CANCELLED); request.setCancelledAt(now);
            request.setCancellationReason(reason); request.setUpdatedAt(now);
            audit.record(request.getRestaurantId(), actor, "QR_ORDER_REQUEST_CANCELLED", "qr_order_requests", request.getId(),
                Map.of("status", "PENDING"), Map.of("status", "CANCELLED", "reason", reason), ip);
        }
        requests.saveAll(pending); requests.flush(); grants.flush();
    }
    private static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
