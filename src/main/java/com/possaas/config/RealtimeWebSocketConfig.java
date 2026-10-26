package com.possaas.config;

import com.possaas.infrastructure.realtime.*;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Configuration @EnableWebSocketMessageBroker
public class RealtimeWebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final RealtimeInboundInterceptor inbound;
    private final RealtimeSessions sessions;
    private final RealtimeErrorHandler errors;
    private final RealtimeHandshakeGuard handshake;
    private final RealtimeOutboundInterceptor outbound;
    private final TaskScheduler scheduler;
    private final String[] origins;

    public RealtimeWebSocketConfig(RealtimeInboundInterceptor inbound, RealtimeSessions sessions,
            RealtimeErrorHandler errors, RealtimeHandshakeGuard handshake, RealtimeOutboundInterceptor outbound,
            @Qualifier("realtimeTaskScheduler") TaskScheduler scheduler,
            @Value("${app.cors.allowed-origins:http://localhost:3000}") String origins) {
        this.inbound = inbound; this.sessions = sessions; this.errors = errors; this.handshake = handshake;
        this.outbound = outbound;
        this.scheduler = scheduler;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(errors);
        registry.addEndpoint("/ws").addInterceptors(handshake).setAllowedOriginPatterns(origins);
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue").setHeartbeatValue(new long[]{10000, 10000}).setTaskScheduler(scheduler);
        registry.setUserDestinationPrefix("/user");
        registry.setApplicationDestinationPrefixes("/app");
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(inbound);
    }
    @Override public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(outbound);
        registration.taskExecutor().corePoolSize(2).maxPoolSize(4).queueCapacity(512);
    }
    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024).setSendBufferSizeLimit(128 * 1024).setSendTimeLimit(10000)
            .setTimeToFirstMessage(15000).addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
                @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                    sessions.opened(session);
                    if (session.isOpen()) super.afterConnectionEstablished(session);
                }
                @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                    sessions.removed(session.getId());
                    super.afterConnectionClosed(session, status);
                }
            });
    }
}
