package com.possaas.infrastructure.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class InventoryRealtimeInboundTest {
    final RealtimeAccess access = mock(RealtimeAccess.class);
    final RealtimeSessions sessions = mock(RealtimeSessions.class);
    final RealtimeInboundInterceptor inbound = new RealtimeInboundInterceptor(access, sessions);
    final RealtimePrincipal principal = new RealtimePrincipal("s1", UUID.randomUUID(), UUID.randomUUID(),
            "OWNER", Instant.parse("2026-12-01T00:00:00Z"));
    final MessageChannel channel = mock(MessageChannel.class);

    StompHeaderAccessor headers(StompCommand command) {
        var h = StompHeaderAccessor.create(command);
        h.setSessionId("s1"); h.setUser(principal); h.setLeaveMutable(true);
        when(sessions.required("s1")).thenReturn(principal);
        return h;
    }
    Message<byte[]> message(StompHeaderAccessor h) { return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders()); }

    @Test void connectUsesOnlyTheStompBearerAndBindsServerChosenPrincipal() {
        var h = headers(StompCommand.CONNECT);
        h.setNativeHeader("Authorization", "Bearer access"); h.setNativeHeader("accept-version", "1.2");
        when(access.authenticate("Bearer access", "s1")).thenReturn(principal);
        inbound.preSend(message(h), channel);
        verify(sessions).authenticate(principal);
        assertThat(h.getUser()).isSameAs(principal);
        assertThat(h.getNativeHeader("Authorization")).isNull();
    }
    @Test void duplicateAuthorizationAndMissingBearerCannotUseHandshakeIdentity() {
        var h = headers(StompCommand.CONNECT); h.setNativeHeader("accept-version", "1.2");
        assertThatThrownBy(() -> inbound.preSend(message(h), channel)).isInstanceOf(RealtimeRejected.class)
                .extracting("code").isEqualTo("UNAUTHORIZED");
        h.addNativeHeader("Authorization", "Bearer one"); h.addNativeHeader("Authorization", "Bearer two");
        assertThatThrownBy(() -> inbound.preSend(message(h), channel)).isInstanceOf(RealtimeRejected.class);
        assertThat(h.getNativeHeader("Authorization")).isNull();
        verify(access, never()).authenticate(anyString(), anyString());
    }
    @Test void invalidProtocolAlsoRemovesBearerBeforeFrameworkErrorHandling() {
        var h = headers(StompCommand.CONNECT);
        h.setNativeHeader("accept-version", "1.1"); h.setNativeHeader("Authorization", "Bearer secret");
        h.setNativeHeader("authorization", "Bearer ignored-secret");
        assertThatThrownBy(() -> inbound.preSend(message(h), channel)).isInstanceOf(RealtimeRejected.class)
                .extracting("code").isEqualTo("REALTIME_PROTOCOL_VIOLATION");
        assertThat(h.getNativeHeader("Authorization")).isNull();
        assertThat(h.getNativeHeader("authorization")).isNull();
        verifyNoInteractions(access);
    }
    @Test void subscriptionOnlyAllowsExactPrivateQueueAndAutoAcknowledgement() {
        var h = headers(StompCommand.SUBSCRIBE);
        h.setNativeHeader("destination", RealtimeInboundInterceptor.DESTINATION);
        h.setNativeHeader("ack", "auto"); h.setNativeHeader("id", "inventory-alerts");
        inbound.preSend(message(h), channel);
        verify(access).requireChannel(principal, RealtimeChannel.INVENTORY_ALERTS);
        verify(sessions).subscribe("s1", "inventory-alerts", RealtimeChannel.INVENTORY_ALERTS);
        for (String destination : new String[]{"/topic/inventory-alerts", "/queue/inventory-alerts", "/user/other/queue/inventory-alerts", "/user/queue/*"}) {
            h.setNativeHeader("destination", destination);
            assertThatThrownBy(() -> inbound.preSend(message(h), channel)).isInstanceOf(RealtimeRejected.class)
                    .extracting("code").isEqualTo("FORBIDDEN");
        }
    }
    @Test void clientCannotPublishOrAcknowledgeBusinessCommandsOverSocket() {
        for (var command : new StompCommand[]{StompCommand.SEND, StompCommand.ACK, StompCommand.NACK}) {
            assertThatThrownBy(() -> inbound.preSend(message(headers(command)), channel)).isInstanceOf(RealtimeRejected.class)
                    .extracting("code").isEqualTo("FORBIDDEN");
        }
        verify(sessions, never()).subscribe(anyString(), anyString(), any());
    }
    @Test void disconnectStillReachesBrokerAfterPhysicalSocketWasRemoved() {
        var h = headers(StompCommand.DISCONNECT);
        h.setUser(null);
        when(sessions.required("s1")).thenThrow(new RealtimeRejected("UNAUTHORIZED"));
        var disconnect = message(h);
        assertThat(inbound.preSend(disconnect, channel)).isSameAs(disconnect);
        assertThat(inbound.preSend(disconnect, channel)).isSameAs(disconnect);
        verify(sessions, times(2)).removed("s1");
        verify(sessions, never()).required(anyString());
        verifyNoInteractions(access);
    }
    @Test void errorFramesExposeOnlyStableCodeAndNoExceptionDetail() {
        var h = headers(StompCommand.SUBSCRIBE); h.setSubscriptionId("inventory-alerts");
        var handler = new RealtimeErrorHandler();
        var output = handler.handleClientMessageProcessingError(message(h),
                new IllegalStateException("SQL/JWT secret detail", new RealtimeRejected("FORBIDDEN")));
        assertThat(new String(output.getPayload(), StandardCharsets.UTF_8)).isEqualTo(
                "{\"code\":\"FORBIDDEN\",\"message\":\"Realtime request rejected\"}");
        var unknown = handler.handleClientMessageProcessingError(null, new RuntimeException("Bearer secret"));
        assertThat(new String(unknown.getPayload(), StandardCharsets.UTF_8)).contains("REALTIME_PROTOCOL_VIOLATION")
                .doesNotContain("secret");
    }
}
