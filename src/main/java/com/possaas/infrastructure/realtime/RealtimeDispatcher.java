package com.possaas.infrastructure.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded best-effort delivery. Neither rejection nor transport errors escape to a committed command. */
@Component
public class RealtimeDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RealtimeDispatcher.class);
    private final Executor executor;
    private final RealtimeSessions sessions;
    private final RealtimeAccess access;
    private final SimpMessagingTemplate messages;
    private final ObjectMapper mapper;
    public RealtimeDispatcher(@Qualifier("realtimeDispatchExecutor") Executor executor,
            RealtimeSessions sessions, RealtimeAccess access, SimpMessagingTemplate messages, ObjectMapper mapper) {
        this.executor = executor; this.sessions = sessions; this.access = access; this.messages = messages; this.mapper = mapper;
    }
    public void enqueue(UUID tenant, RealtimeEventEnvelope event, Set<RealtimeChannel> channels) {
        enqueueBatch(tenant, List.of(event), channels);
    }
    public void enqueueBatch(UUID tenant, List<RealtimeEventEnvelope> events, Set<RealtimeChannel> channels) {
        try {
            var destinations = Set.copyOf(channels);
            var snapshots = List.copyOf(events);
            if (snapshots.isEmpty()) return;
            if (snapshots.size() > 100) throw new IllegalArgumentException("Realtime batch exceeds limit");
            executor.execute(() -> {
                try { deliver(tenant, snapshots, destinations); }
                catch (Exception failure) { log.warn("Realtime delivery dropped"); }
            });
        } catch (RuntimeException full) { log.warn("Realtime dispatch rejected"); }
    }
    private void deliver(UUID tenant, List<RealtimeEventEnvelope> events, Set<RealtimeChannel> channels) throws Exception {
        var recipients = sessions.subscribers(tenant).stream()
                .filter(p -> channels.stream().anyMatch(c -> sessions.subscribed(p, c))).toList();
        if (recipients.isEmpty()) return;
        List<byte[]> bodies = new ArrayList<>();
        for (var event : events) bodies.add(encode(event));
        var decisions = access.authorizeBatch(tenant, recipients, channels);
        for (var principal : recipients) {
            var decision = decisions.get(principal.sessionId());
            if (decision == null) continue;
            if (decision.rejection() != null) { sessions.close(principal.sessionId(), decision.rejection()); continue; }
            for (var channel : channels) {
                if (!decision.channels().contains(channel)) { sessions.fence(principal.sessionId(), channel); continue; }
                if (!sessions.subscribed(principal, channel)) continue;
                for (byte[] body : bodies) try {
                    if (!sessions.subscribed(principal, channel)) break;
                    var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
                    headers.setSessionId(principal.sessionId()); headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setLeaveMutable(true);
                    messages.send(messages.getUserDestinationPrefix() + principal.getName() + channel.queue(),
                            MessageBuilder.createMessage(body, headers.getMessageHeaders()));
                } catch (RuntimeException unavailable) { log.debug("Realtime recipient unavailable"); }
            }
        }
    }
    private byte[] encode(RealtimeEventEnvelope event) throws Exception {
        var data = new LinkedHashMap<String, Object>();
        data.put("eventId", event.eventId().toString()); data.put("type", event.type());
        data.put("schemaVersion", event.schemaVersion()); data.put("resourceType", event.resourceType());
        data.put("resourceId", event.resourceId().toString()); data.put("resourceVersion", event.resourceVersion());
        data.put("occurredAt", event.occurredAt().toString()); data.put("payload", event.payload());
        // Encode once, avoiding converter-dependent base64 encoding of JSON byte[].
        return mapper.writeValueAsBytes(data);
    }
}
