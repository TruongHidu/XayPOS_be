package com.possaas.infrastructure.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.Message;

class InventoryRealtimeBroadcasterTest {
    final UUID tenant = UUID.randomUUID(), resource = UUID.randomUUID();
    final RealtimeSessions sessions = mock(RealtimeSessions.class);
    final RealtimeAccess access = mock(RealtimeAccess.class);
    final SimpMessagingTemplate messages = mock(SimpMessagingTemplate.class);
    final ObjectMapper mapper = new ObjectMapper();
    final InventoryRealtimeBroadcaster broadcaster = new InventoryRealtimeBroadcaster(new RealtimeDispatcher(Runnable::run, sessions, access, messages, mapper));

    @BeforeEach void destinationPrefix() {
        when(messages.getUserDestinationPrefix()).thenReturn("/user/");
        when(sessions.subscribed(any(),any())).thenReturn(true);
        when(access.authorizeBatch(any(),anyList(),anySet())).thenAnswer(c -> {
            java.util.Map<String,RealtimeAccess.Decision> result = new java.util.HashMap<>();
            for (RealtimePrincipal p : c.<List<RealtimePrincipal>>getArgument(1))
                result.put(p.sessionId(),new RealtimeAccess.Decision(java.util.Set.of(RealtimeChannel.INVENTORY_ALERTS),null));
            return result;
        });
    }

    RealtimePrincipal principal(String id) {
        return new RealtimePrincipal(id, UUID.randomUUID(), tenant, "OWNER", Instant.parse("2026-12-01T00:00:00Z"));
    }
    InventoryAlertChangedEvent event() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("warehouseId", UUID.randomUUID()); payload.put("ingredientId", UUID.randomUUID());
        payload.put("status", "OPEN"); payload.put("resolutionReason", null);
        return new InventoryAlertChangedEvent(tenant, new RealtimeEventEnvelope(UUID.randomUUID(),
                "INVENTORY_LOW_STOCK_ALERT_CHANGED", 1, "LOW_STOCK_ALERT", resource, 3,
                Instant.parse("2026-10-24T08:00:00Z"), payload));
    }
    @Test void emitsRawJsonBytesWithIsoTimeWithoutDependingOnATimeModuleOrBrokerJsonConverter() throws Exception {
        var principal = principal("s1"); when(sessions.subscribers(tenant)).thenReturn(List.of(principal));
        when(sessions.subscribed(principal, RealtimeChannel.INVENTORY_ALERTS)).thenReturn(true);
        broadcaster.changed(event());
        var sent = ArgumentCaptor.forClass(Message.class);
        verify(messages).send(eq("/user/s1/queue/inventory-alerts"), sent.capture());
        assertThat(sent.getValue().getPayload()).isInstanceOf(byte[].class);
        var parsed = mapper.readTree((byte[]) sent.getValue().getPayload());
        assertThat(parsed.isObject()).isTrue();
        assertThat(parsed.get("occurredAt").asText()).isEqualTo("2026-10-24T08:00:00Z");
        assertThat(parsed.get("resourceId").asText()).isEqualTo(resource.toString());
        assertThat(parsed.get("resourceVersion").asLong()).isEqualTo(3);
        assertThat(parsed.get("payload").get("resolutionReason").isNull()).isTrue();
        verify(access).authorizeBatch(eq(tenant),eq(List.of(principal)),eq(java.util.Set.of(RealtimeChannel.INVENTORY_ALERTS)));
    }
    @Test void revocationClosesThatSocketWithoutBlockingOtherAuthorizedRecipients() {
        var denied = principal("denied"); var allowed = principal("allowed");
        when(sessions.subscribers(tenant)).thenReturn(List.of(denied, allowed));
        when(sessions.subscribed(allowed, RealtimeChannel.INVENTORY_ALERTS)).thenReturn(true);
        when(access.authorizeBatch(any(),anyList(),anySet())).thenReturn(java.util.Map.of(
            denied.sessionId(),new RealtimeAccess.Decision(java.util.Set.of(),"FORBIDDEN"),
            allowed.sessionId(),new RealtimeAccess.Decision(java.util.Set.of(RealtimeChannel.INVENTORY_ALERTS),null)));
        assertThatCode(() -> broadcaster.changed(event())).doesNotThrowAnyException();
        verify(sessions).close("denied", "FORBIDDEN");
        verify(messages, never()).send(eq("/user/denied/queue/inventory-alerts"), any(Message.class));
        verify(messages).send(eq("/user/allowed/queue/inventory-alerts"), any(Message.class));
    }
    @Test void disconnectedRecipientsAndFailedDeliveryCannotFailCommittedStockOperations() {
        var removed = principal("removed"); var failed = principal("failed");
        when(sessions.subscribers(tenant)).thenReturn(List.of(removed, failed));
        when(sessions.subscribed(failed, RealtimeChannel.INVENTORY_ALERTS)).thenReturn(true);
        when(sessions.subscribed(removed, RealtimeChannel.INVENTORY_ALERTS)).thenReturn(false);
        doThrow(new IllegalStateException("Socket closed")).when(messages)
                .send(eq("/user/failed/queue/inventory-alerts"), any(Message.class));
        assertThatCode(() -> broadcaster.changed(event())).doesNotThrowAnyException();
        verify(messages, never()).send(eq("/user/removed/queue/inventory-alerts"), any(Message.class));
    }
}
