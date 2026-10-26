package com.possaas.infrastructure.realtime;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
public class RealtimeSessions {
    private static final class Connection {
        final WebSocketSession socket;
        RealtimePrincipal principal;
        final Map<String, Subscription> subscriptions = new HashMap<>();
        ScheduledFuture<?> deadline;
        Connection(WebSocketSession socket) { this.socket = socket; }
    }
    private final Map<String, Connection> connections = new HashMap<>();
    private final TaskScheduler scheduler;
    private final int maxConnections, perUserLimit, subscriptionLimit;
    private record Subscription(RealtimeChannel channel, boolean allowed) {}

    public RealtimeSessions(@Qualifier("realtimeTaskScheduler") TaskScheduler scheduler,
            @Value("${app.realtime.max-connections:10000}") int maxConnections,
            @Value("${app.realtime.max-connections-per-user:8}") int perUserLimit,
            @Value("${app.realtime.max-subscriptions-per-session:4}") int subscriptionLimit) {
        this.scheduler = scheduler;
        this.maxConnections = Math.max(1, maxConnections);
        this.perUserLimit = Math.max(1, perUserLimit);
        this.subscriptionLimit = Math.max(1, Math.min(16, subscriptionLimit));
    }

    public synchronized void opened(WebSocketSession socket) throws IOException {
        if (connections.size() >= maxConnections) {
            socket.close(new CloseStatus(1008, "REALTIME_CONNECTION_LIMIT"));
            return;
        }
        var connection = new Connection(socket);
        connections.put(socket.getId(), connection);
        connection.deadline = scheduler.schedule(() -> closeIfUnauthenticated(socket.getId(), connection),
                scheduler.getClock().instant().plusSeconds(15));
    }

    private synchronized void closeIfUnauthenticated(String id, Connection expected) {
        if (connections.get(id) == expected && expected.principal == null) close(id, "UNAUTHORIZED");
    }

    public synchronized void authenticate(RealtimePrincipal principal) {
        var connection = connections.get(principal.sessionId());
        if (connection == null || connection.principal != null) throw new RealtimeRejected("UNAUTHORIZED");
        long count = connections.values().stream().filter(c -> c.principal != null
                && c.principal.userId().equals(principal.userId())).count();
        if (count >= perUserLimit) throw new RealtimeRejected("REALTIME_CONNECTION_LIMIT");
        connection.principal = principal;
        cancel(connection);
        connection.deadline = scheduler.schedule(() -> closeExpired(principal), principal.expiresAt());
    }

    private synchronized void closeExpired(RealtimePrincipal expected) {
        var connection = connections.get(expected.sessionId());
        if (connection != null && connection.principal == expected) close(expected.sessionId(), "TOKEN_EXPIRED");
    }

    public synchronized RealtimePrincipal required(String id) {
        var connection = connections.get(id);
        if (connection == null || connection.principal == null) throw new RealtimeRejected("UNAUTHORIZED");
        if (!connection.principal.expiresAt().isAfter(scheduler.getClock().instant())) {
            close(id, "TOKEN_EXPIRED");
            throw new RealtimeRejected("TOKEN_EXPIRED");
        }
        return connection.principal;
    }

    public synchronized void subscribe(String id, String subscription, RealtimeChannel channel) {
        required(id);
        var connection = connections.get(id);
        if (subscription == null || !subscription.matches("[A-Za-z0-9._:-]{1,100}")
                || connection.subscriptions.containsKey(subscription)
                || connection.subscriptions.values().stream().anyMatch(s -> s.channel() == channel))
            throw new RealtimeRejected("REALTIME_PROTOCOL_VIOLATION");
        if (connection.subscriptions.size() >= subscriptionLimit) throw new RealtimeRejected("REALTIME_SUBSCRIPTION_LIMIT");
        connection.subscriptions.put(subscription, new Subscription(channel, true));
    }

    public synchronized void unsubscribe(String id, String subscription) {
        required(id);
        var connection = connections.get(id);
        if (connection.subscriptions.remove(subscription) == null) throw new RealtimeRejected("REALTIME_PROTOCOL_VIOLATION");
    }

    public synchronized List<RealtimePrincipal> subscribers(UUID restaurantId) {
        return connections.values().stream().filter(c -> c.principal != null && c.subscriptions.values().stream().anyMatch(Subscription::allowed)
                && c.principal.restaurantId().equals(restaurantId)).map(c -> c.principal).toList();
    }

    public synchronized boolean subscribed(RealtimePrincipal principal, RealtimeChannel channel) {
        var connection = connections.get(principal.sessionId());
        return connection != null && connection.principal == principal && connection.socket.isOpen()
                && principal.expiresAt().isAfter(scheduler.getClock().instant())
                && connection.subscriptions.values().stream().anyMatch(s -> s.allowed() && s.channel() == channel);
    }

    public synchronized void fence(String sessionId, RealtimeChannel channel) {
        var connection = connections.get(sessionId);
        if (connection != null) connection.subscriptions.replaceAll((id, s) ->
                s.channel() == channel ? new Subscription(channel, false) : s);
    }

    /** Also called by ExecutorChannelInterceptor after the outbound task leaves its queue. */
    public synchronized boolean mayDeliver(String sessionId, String subscriptionId, String destination) {
        var connection = connections.get(sessionId);
        if (connection == null || connection.principal == null) return false;
        var subscription = connection.subscriptions.get(subscriptionId);
        if (subscription == null || !subscription.allowed()) return false;
        if (!connection.principal.expiresAt().isAfter(scheduler.getClock().instant())) {
            close(sessionId, "TOKEN_EXPIRED"); return false;
        }
        return connection.socket.isOpen()
                && (subscription.channel().queue() + "-user" + sessionId).equals(destination);
    }

    @PreDestroy public synchronized void shutdown() {
        for (String id : List.copyOf(connections.keySet())) close(id, "REALTIME_SHUTDOWN");
    }

    public synchronized void removed(String id) {
        var connection = connections.remove(id);
        if (connection != null) cancel(connection);
    }

    public synchronized void close(String id, String code) {
        var connection = connections.remove(id);
        if (connection == null) return;
        cancel(connection);
        try { connection.socket.close(new CloseStatus(1008, code)); }
        catch (IOException | RuntimeException ignored) { /* The connection has already been fenced. */ }
    }

    private void cancel(Connection connection) {
        if (connection.deadline != null) connection.deadline.cancel(false);
    }
}
