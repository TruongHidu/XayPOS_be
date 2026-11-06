package com.possaas.infrastructure.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class RealtimeOutboundInterceptor implements ExecutorChannelInterceptor {
    private final RealtimeSessions sessions;
    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) { return filter(message); }
    @Override public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        return filter(message);
    }
    private Message<?> filter(Message<?> message) {
        var headers = message.getHeaders();
        if (SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE) return message;
        return sessions.mayDeliver(SimpMessageHeaderAccessor.getSessionId(headers),
                SimpMessageHeaderAccessor.getSubscriptionId(headers), SimpMessageHeaderAccessor.getDestination(headers))
                ? message : null;
    }
}
