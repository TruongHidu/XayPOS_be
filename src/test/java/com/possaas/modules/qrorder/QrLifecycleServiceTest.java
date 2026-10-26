package com.possaas.modules.qrorder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.repository.*;
import com.possaas.modules.qrorder.service.*;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class QrLifecycleServiceTest {
    private final UUID tenant=UUID.randomUUID(),tableId=UUID.randomUUID(),sessionId=UUID.randomUUID(),actor=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-14T00:00:00Z");
    private final QrTableAccessRepository access=mock(QrTableAccessRepository.class);
    private final QrGuestAuthorizationRepository grants=mock(QrGuestAuthorizationRepository.class);
    private final QrOrderRequestRepository requests=mock(QrOrderRequestRepository.class);
    private final RestaurantTableRepository tables=mock(RestaurantTableRepository.class);
    private final TableSessionRepository sessions=mock(TableSessionRepository.class);
    private final AuditService audit=mock(AuditService.class);
    private final QrLifecycleService lifecycle=new QrLifecycleService(access,grants,mock(com.possaas.modules.qrorder.service.QrRequestEventPublisher.class),requests,tables,sessions,audit);

    @Test void tableInvalidationBumpsSeparateEpochRevokesAccessAndCancelsPendingWithoutChangingPhysicalTable() {
        var epoch=new QrTableAccess(); epoch.setRestaurantId(tenant); epoch.setTableId(tableId); epoch.setEpoch(4);
        var pending=request(QrEntryType.TABLE,QrRequestStatus.PENDING); var grant=new QrGuestAuthorization(); grant.setExpiresAt(now.plusSeconds(3600));
        when(access.findByTableIdAndRestaurantId(tableId,tenant)).thenReturn(Optional.of(epoch));
        when(grants.findAllByRestaurantIdAndTableIdAndRevokedAtIsNull(tenant,tableId)).thenReturn(List.of(grant));
        when(requests.findAllByRestaurantIdAndTableIdAndStatus(tenant,tableId,QrRequestStatus.PENDING)).thenReturn(List.of(pending));
        lifecycle.invalidateTable(tenant,tableId,"SESSION_CLOSED",actor,now,"127.0.0.1");
        assertThat(epoch.getEpoch()).isEqualTo(5); assertThat(epoch.getUpdatedAt()).isEqualTo(now);
        assertThat(grant.getRevokedAt()).isEqualTo(now); assertThat(grant.getExpiresAt()).isEqualTo(now.plusSeconds(3600));
        assertThat(pending.getStatus()).isEqualTo(QrRequestStatus.CANCELLED); assertThat(pending.getCancellationReason()).isEqualTo("SESSION_CLOSED");
        assertThat(pending.getCancelledAt()).isEqualTo(now); assertThat(pending.getUpdatedAt()).isEqualTo(now);
        verifyNoInteractions(tables,sessions);
        verify(audit).record(eq(tenant),eq(actor),eq("QR_ORDER_REQUEST_CANCELLED"),eq("qr_order_requests"),eq(pending.getId()),anyMap(),anyMap(),eq("127.0.0.1"));
    }
    @Test void checkoutCancelsOnlyExactCurrentTableEpochCredentialAndObservedSession() {
        var session=new TableSession(); session.setId(sessionId); session.setTableId(tableId);
        var table=new RestaurantTable(); table.setId(tableId); table.setQrToken("credential"); table.setVersion(12);
        var epoch=new QrTableAccess(); epoch.setEpoch(7);
        when(sessions.findByIdAndRestaurantId(sessionId,tenant)).thenReturn(Optional.of(session));
        when(tables.findByIdAndRestaurantId(tableId,tenant)).thenReturn(Optional.of(table));
        when(access.findByTableIdAndRestaurantId(tableId,tenant)).thenReturn(Optional.of(epoch));
        var current=request(QrEntryType.TABLE,QrRequestStatus.PENDING); current.setTableAccessEpoch(7L);
        var priorEpoch=request(QrEntryType.TABLE,QrRequestStatus.PENDING); priorEpoch.setTableAccessEpoch(6L);
        var priorCredential=request(QrEntryType.TABLE,QrRequestStatus.PENDING); priorCredential.setCredentialHash(QrHashes.digest("old-credential"));
        var otherTable=request(QrEntryType.TABLE,QrRequestStatus.PENDING); otherTable.setTableId(UUID.randomUUID());
        var restaurant=request(QrEntryType.RESTAURANT,QrRequestStatus.PENDING);
        when(requests.findAllByRestaurantIdAndObservedSessionIdAndStatus(tenant,sessionId,QrRequestStatus.PENDING))
                .thenReturn(List.of(current,priorEpoch,priorCredential,otherTable,restaurant));
        lifecycle.checkoutStarted(tenant,sessionId,actor,now,"127.0.0.1");
        assertThat(current.getStatus()).isEqualTo(QrRequestStatus.CANCELLED); assertThat(current.getCancellationReason()).isEqualTo("CHECKOUT_STARTED");
        for(var unaffected:List.of(priorEpoch,priorCredential,otherTable,restaurant)) assertThat(unaffected.getStatus()).isEqualTo(QrRequestStatus.PENDING);
        assertThat(table.getVersion()).isEqualTo(12); assertThat(epoch.getEpoch()).isEqualTo(7);
        verify(grants).flush(); verifyNoMoreInteractions(grants);
        verify(requests).saveAll(List.of(current));
    }
    @Test void restaurantRotationOnlyTouchesOldRestaurantScopeAndNeverTables() {
        var pending=request(QrEntryType.RESTAURANT,QrRequestStatus.PENDING); var grant=new QrGuestAuthorization();
        var hash=QrHashes.digest("old-menu");
        when(grants.findAllByRestaurantIdAndEntryTypeAndCredentialHashAndRevokedAtIsNull(tenant,QrEntryType.RESTAURANT,hash)).thenReturn(List.of(grant));
        when(requests.findAllByRestaurantIdAndEntryTypeAndCredentialHashAndStatus(tenant,QrEntryType.RESTAURANT,hash,QrRequestStatus.PENDING)).thenReturn(List.of(pending));
        lifecycle.invalidateRestaurantCredential(tenant,"old-menu",actor,now,"127.0.0.1");
        assertThat(grant.getRevokedAt()).isEqualTo(now); assertThat(pending.getStatus()).isEqualTo(QrRequestStatus.CANCELLED);
        assertThat(pending.getCancellationReason()).isEqualTo("RESTAURANT_MENU_TOKEN_ROTATED"); verifyNoInteractions(access,tables,sessions);
    }
    @Test void alreadyCancelledRowsDoNotProduceDuplicateSuccessAudit() {
        var cancelled=request(QrEntryType.TABLE,QrRequestStatus.CANCELLED); cancelled.setCancelledAt(now.minusSeconds(5)); cancelled.setCancellationReason("ORIGINAL");
        when(requests.findAllByRestaurantIdAndTableIdAndStatus(tenant,tableId,QrRequestStatus.PENDING)).thenReturn(List.of(cancelled));
        lifecycle.invalidateTable(tenant,tableId,"SESSION_CLOSED",actor,now,"127.0.0.1");
        assertThat(cancelled.getCancellationReason()).isEqualTo("ORIGINAL"); assertThat(cancelled.getCancelledAt()).isEqualTo(now.minusSeconds(5)); verifyNoInteractions(audit);
    }
    private QrOrderRequest request(QrEntryType type,QrRequestStatus status) {
        var request=new QrOrderRequest(); request.setId(UUID.randomUUID()); request.initialize(tenant,now.minusSeconds(30));
        request.setEntryType(type); request.setStatus(status); request.setTableId(type==QrEntryType.TABLE?tableId:null);
        request.setObservedSessionId(type==QrEntryType.TABLE?sessionId:null); request.setTableAccessEpoch(type==QrEntryType.TABLE?7L:null);
        request.setCredentialHash(QrHashes.digest("credential")); return request;
    }
}
