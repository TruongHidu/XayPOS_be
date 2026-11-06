package com.possaas.infrastructure.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class RealtimeSessionsTest {
    final Instant now = Instant.parse("2026-10-24T08:00:00Z");
    final TaskScheduler scheduler = mock(TaskScheduler.class);
    final List<Runnable> tasks = new ArrayList<>();
    final List<ScheduledFuture<?>> futures = new ArrayList<>();
    final RealtimeSessions sessions = new RealtimeSessions(scheduler, 10, 2, 4);
    final UUID user = UUID.randomUUID(), tenant = UUID.randomUUID();

    @BeforeEach @SuppressWarnings({"unchecked", "rawtypes"}) void scheduler() {
        when(scheduler.getClock()).thenReturn(Clock.fixed(now, ZoneOffset.UTC));
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(0));
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            futures.add(future);
            return future;
        });
    }
    WebSocketSession socket(String id) throws Exception {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id); when(socket.isOpen()).thenReturn(true);
        sessions.opened(socket);
        return socket;
    }
    RealtimePrincipal principal(String id, UUID tenantId) {
        return new RealtimePrincipal(id, user, tenantId, "OWNER", now.plusSeconds(60));
    }
    @Test void unauthenticatedConnectionsExpireAndCannotBeUsedLater() throws Exception {
        var socket = socket("s1"); tasks.getFirst().run();
        verify(socket).close(new CloseStatus(1008, "UNAUTHORIZED"));
        assertThatThrownBy(() -> sessions.authenticate(principal("s1", tenant))).isInstanceOf(RealtimeRejected.class);
    }
    @Test void authenticatingCancelsHandshakeDeadlineAndExpiryClosesOnlyThatSession() throws Exception {
        var socket = socket("s1"); var principal = principal("s1", tenant);
        sessions.authenticate(principal);
        verify(futures.getFirst()).cancel(false);
        tasks.getFirst().run(); verify(socket, never()).close(any());
        tasks.get(1).run(); verify(socket).close(new CloseStatus(1008, "TOKEN_EXPIRED"));
        assertThatThrownBy(() -> sessions.required("s1")).isInstanceOf(RealtimeRejected.class);
    }
    @Test void subscribersAreLimitedToAuthenticatedSubscribedSocketsOfTheSameTenant() throws Exception {
        socket("s1"); socket("s2");
        var first = principal("s1", tenant); var other = principal("s2", UUID.randomUUID());
        sessions.authenticate(first); sessions.authenticate(other);
        assertThat(sessions.subscribers(tenant)).isEmpty();
        sessions.subscribe("s1", "inventory-alerts", RealtimeChannel.INVENTORY_ALERTS); sessions.subscribe("s2", "inventory-alerts", RealtimeChannel.INVENTORY_ALERTS);
        assertThat(sessions.subscribers(tenant)).containsExactly(first);
        sessions.unsubscribe("s1", "inventory-alerts"); assertThat(sessions.subscribers(tenant)).isEmpty();
        assertThatThrownBy(() -> sessions.unsubscribe("s2", "wrong-id")).isInstanceOf(RealtimeRejected.class);
    }
    @Test void removingConnectionCancelsExpiryAndInvalidatesQueuedDelivery() throws Exception {
        socket("s1"); var principal = principal("s1", tenant);
        sessions.authenticate(principal); sessions.subscribe("s1", "inventory-alerts", RealtimeChannel.INVENTORY_ALERTS);
        sessions.removed("s1");
        verify(futures.get(1)).cancel(false);
        assertThat(sessions.subscribed(principal, RealtimeChannel.INVENTORY_ALERTS)).isFalse();
        assertThat(sessions.subscribers(tenant)).isEmpty();
        tasks.get(1).run();
    }
    @Test void limitsUnauthenticatedConnectionsAndConnectionsPerUser() throws Exception {
        var limited = new RealtimeSessions(scheduler, 1, 1, 1);
        var first = mock(WebSocketSession.class); when(first.getId()).thenReturn("first");
        var second = mock(WebSocketSession.class); when(second.getId()).thenReturn("second");
        limited.opened(first); limited.opened(second);
        verify(second).close(new CloseStatus(1008, "REALTIME_CONNECTION_LIMIT"));
        socket("s1"); socket("s2"); socket("s3");
        sessions.authenticate(principal("s1", tenant)); sessions.authenticate(principal("s2", tenant));
        assertThatThrownBy(() -> sessions.authenticate(principal("s3", tenant)))
                .isInstanceOf(RealtimeRejected.class).extracting("code").isEqualTo("REALTIME_CONNECTION_LIMIT");
    }
}
