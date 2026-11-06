package com.possaas.infrastructure.realtime;

import java.nio.charset.StandardCharsets;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

@Component
public class RealtimeErrorHandler extends StompSubProtocolErrorHandler {
    @Override public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable error) {
        String code = "REALTIME_PROTOCOL_VIOLATION";
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RealtimeRejected rejected) { code = rejected.code(); break; }
            if (cause.getCause() == cause) break;
        }
        var headers = StompHeaderAccessor.create(StompCommand.ERROR);
        headers.setMessage(code);
        headers.setNativeHeader("code", code);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        if (clientMessage != null) {
            var client = MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class);
            if (client != null && client.getSubscriptionId() != null) {
                headers.setNativeHeader("subscription", client.getSubscriptionId());
            }
        }
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"Realtime request rejected\"}").getBytes(StandardCharsets.UTF_8);
        return MessageBuilder.createMessage(body, headers.getMessageHeaders());
    }
}
