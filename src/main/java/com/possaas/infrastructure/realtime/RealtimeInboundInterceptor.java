package com.possaas.infrastructure.realtime;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class RealtimeInboundInterceptor implements ChannelInterceptor {
    public static final String DESTINATION = "/user/queue/inventory-alerts";
    private final RealtimeAccess access;
    private final RealtimeSessions sessions;

    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || headers.getSessionId() == null) throw new RealtimeRejected("REALTIME_PROTOCOL_VIOLATION");
        String id = headers.getSessionId();
        StompCommand command = headers.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            try {
                if (!"1.2".equals(single(headers, "accept-version"))) throw new RealtimeRejected("REALTIME_PROTOCOL_VIOLATION");
                rejectIdentityHeaders(headers);
                if (headers.toNativeHeaderMap().keySet().stream().anyMatch(k ->
                        k.equalsIgnoreCase("Authorization") && !k.equals("Authorization")))
                    throw new RealtimeRejected("UNAUTHORIZED");
                var principal = access.authenticate(single(headers, "Authorization"), id);
                sessions.authenticate(principal);
                headers.setUser(principal);
                return message;
            } finally {
                // Framework diagnostics may retain failed messages: never retain the bearer header.
                for (var key : java.util.List.copyOf(headers.toNativeHeaderMap().keySet()))
                    if (key.equalsIgnoreCase("Authorization")) headers.removeNativeHeader(key);
            }
        }
        // Spring also emits DISCONNECT after the physical socket has already been removed.
        // Always let this idempotent cleanup reach the broker to release its subscriptions.
        if (command == StompCommand.DISCONNECT) {
            sessions.removed(id);
            return message;
        }
        var principal = sessions.required(id);
        if (!(headers.getUser() instanceof RealtimePrincipal user) || !user.equals(principal)) {
            throw new RealtimeRejected("UNAUTHORIZED");
        }
        rejectIdentityHeaders(headers);
        if (headers.getMessageType() == SimpMessageType.HEARTBEAT) return message;
        if (command == StompCommand.SUBSCRIBE) {
            var destination = RealtimeChannel.fromDestination(single(headers, "destination"));
            if (!"auto".equals(single(headers, "ack"))
                    || headers.getNativeHeader("selector") != null) throw new RealtimeRejected("FORBIDDEN");
            access.requireChannel(principal, destination);
            sessions.subscribe(id, single(headers, "id"), destination);
        } else if (command == StompCommand.UNSUBSCRIBE) {
            sessions.unsubscribe(id, single(headers, "id"));
        } else {
            throw new RealtimeRejected("FORBIDDEN");
        }
        return message;
    }

    private static void rejectIdentityHeaders(StompHeaderAccessor headers) {
        for (String key : headers.toNativeHeaderMap().keySet()) {
            if (java.util.Set.of("restaurantid", "restaurant_id", "x-restaurant-id", "tenantid", "tenant_id",
                    "x-tenant-id", "userid", "user_id", "x-user-id", "simpuser", "simpsessionid", "login", "passcode")
                    .contains(key.toLowerCase(java.util.Locale.ROOT))) throw new RealtimeRejected("FORBIDDEN");
        }
    }

    private static String single(StompHeaderAccessor headers, String key) {
        var values = headers.getNativeHeader(key);
        if (values == null || values.size() != 1 || values.getFirst() == null
                || values.getFirst().isBlank() || values.getFirst().length() > ("Authorization".equals(key) ? 8192 : 200)) {
            throw new RealtimeRejected("Authorization".equals(key) ? "UNAUTHORIZED" : "REALTIME_PROTOCOL_VIOLATION");
        }
        return values.getFirst();
    }
}
