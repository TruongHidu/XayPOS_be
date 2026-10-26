package com.possaas.infrastructure.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.config.RealtimeSchedulerConfig;
import com.possaas.config.RealtimeDispatchConfig;
import com.possaas.config.RealtimeWebSocketConfig;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Real TCP/WebSocket/STOMP and after-commit delivery, without an application database. */
@SpringBootTest(classes = InventoryRealtimeWebSocketTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.banner-mode=off", "logging.level.root=WARN",
                "app.cors.allowed-origins=http://localhost:3000,https://*.pos.example"})
@ActiveProfiles("inventory-realtime-socket-test")
class InventoryRealtimeWebSocketTest {
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID USER_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID USER_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Duration WAIT = Duration.ofSeconds(5);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(WAIT).build();
    private final List<SocketClient> sockets = new ArrayList<>();

    @LocalServerPort int port;
    @Autowired RealtimeAccess access;
    @Autowired RealtimeSessions sessions;
    @Autowired SimpleBrokerMessageHandler broker;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;

    @Configuration(proxyBeanMethods = false)
    @Profile("inventory-realtime-socket-test")
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"})
    @EnableTransactionManagement
    @Import({RealtimeWebSocketConfig.class, RealtimeSchedulerConfig.class, RealtimeSessions.class,
            RealtimeInboundInterceptor.class, RealtimeErrorHandler.class,
            RealtimeHandshakeGuard.class, InventoryRealtimeBroadcaster.class, RealtimeDispatcher.class,
            RealtimeOutboundInterceptor.class, RealtimeDispatchConfig.class, OrderRealtimeAdapter.class})
    static class TestApplication {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean RealtimeAccess inventoryRealtimeAccess() { return mock(RealtimeAccess.class); }
        @Bean PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
                @Override protected void doCommit(DefaultTransactionStatus status) {}
                @Override protected void doRollback(DefaultTransactionStatus status) {}
            };
        }
        @Bean SecurityFilterChain testHttpSecurity(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }

    @BeforeEach void configureTokenIdentityWithoutAnyDatabase() {
        reset(access);
        when(access.authorizeBatch(any(),anyList(),anySet())).thenAnswer(c -> {
            Map<String,RealtimeAccess.Decision> result = new LinkedHashMap<>();
            for (RealtimePrincipal p : c.<List<RealtimePrincipal>>getArgument(1))
                result.put(p.sessionId(),new RealtimeAccess.Decision(c.getArgument(2),null));
            return result;
        });
        when(access.authenticate(anyString(), anyString())).thenAnswer(call -> {
            String authorization = call.getArgument(0), session = call.getArgument(1);
            return switch (authorization) {
                case "Bearer tenant-a" -> new RealtimePrincipal(session, USER_A, TENANT_A,
                        "OWNER", Instant.now().plusSeconds(3600));
                case "Bearer tenant-b" -> new RealtimePrincipal(session, USER_B, TENANT_B,
                        "OWNER", Instant.now().plusSeconds(3600));
                case "Bearer short-a" -> new RealtimePrincipal(session, USER_A, TENANT_A,
                        "OWNER", Instant.now().plusSeconds(2));
                default -> throw new RealtimeRejected("UNAUTHORIZED");
            };
        });
    }

    @AfterEach void closeSockets() {
        var subscribers = new ArrayList<>(sessions.subscribers(TENANT_A));
        subscribers.addAll(sessions.subscribers(TENANT_B));
        sockets.forEach(SocketClient::close);
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(sessions.subscribers(TENANT_A)).isEmpty();
            assertThat(sessions.subscribers(TENANT_B)).isEmpty();
            for (var principal : subscribers) {
                assertThat(broker.getSubscriptionRegistry().findSubscriptions(subscriptionMessage(principal)))
                        .doesNotContainKey(principal.sessionId());
            }
        });
        client.close();
    }

    @Test void nativeStompDeliversCommittedEnvelopeOnlyToTheAuthorizedTenant() throws Exception {
        var own = connect("tenant-a");
        var other = connect("tenant-b");
        subscribe(own, TENANT_A);
        subscribe(other, TENANT_B);
        var committed = event(TENANT_A, 7);
        var template = new TransactionTemplate(transactions);

        template.executeWithoutResult(status -> {
            events.publishEvent(committed);
            // The listener must not send before its enclosing inventory transaction commits.
            assertThat(own.messages).isEmpty();
        });
        var frame = own.awaitFrame("MESSAGE");
        JsonNode body = json.readTree(frame.body());
        assertThat(frame.headers()).containsEntry("subscription", "inventory-alerts");
        assertThat(body.path("eventId").asText()).isEqualTo(committed.envelope().eventId().toString());
        assertThat(body.path("type").asText()).isEqualTo("INVENTORY_LOW_STOCK_ALERT_CHANGED");
        assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.path("resourceType").asText()).isEqualTo("LOW_STOCK_ALERT");
        assertThat(body.path("resourceId").asText()).isEqualTo(committed.envelope().resourceId().toString());
        assertThat(body.path("resourceVersion").asLong()).isEqualTo(7);
        assertThat(body.path("occurredAt").asText()).isEqualTo(committed.envelope().occurredAt().toString());
        assertThat(body.path("payload").path("status").asText()).isEqualTo("OPEN");
        assertThat(body.has("restaurantId")).isFalse();
        assertThat(other.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();

        template.executeWithoutResult(status -> {
            events.publishEvent(event(TENANT_A, 8));
            status.setRollbackOnly();
        });
        assertThat(own.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
        assertThat(other.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test void connectWithoutAuthorizationAndClientBusinessSendAreRejected() throws Exception {
        var anonymous = raw();
        anonymous.send("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\u0000");
        assertThat(anonymous.awaitFrame("ERROR").headers()).containsEntry("code", "UNAUTHORIZED");

        var authenticated = connect("tenant-a");
        authenticated.send("SEND\ndestination:/app/inventory-alerts\ncontent-type:application/json\n\n{}\u0000");
        assertThat(authenticated.awaitFrame("ERROR").headers()).containsEntry("code", "FORBIDDEN");
    }

    @Test void aRawQueueSubscriptionCannotBypassTheUserDestination() throws Exception {
        var socket = connect("tenant-a");
        socket.send("SUBSCRIBE\nid:inventory-alerts\ndestination:/queue/inventory-alerts\nack:auto\n\n\u0000");
        assertThat(socket.awaitFrame("ERROR").headers()).containsEntry("code", "FORBIDDEN");
        assertThat(sessions.subscribers(TENANT_A)).isEmpty();
    }

    @Test void webOriginUsesTheSameAllowedPatternsAsRest() {
        raw("https://kitchen.pos.example");
        var failure = catchThrowable(() -> raw("https://untrusted.example"));
        assertThat(failure).hasCauseInstanceOf(WebSocketHandshakeException.class);
        var handshake = (WebSocketHandshakeException) failure.getCause();
        assertThat(handshake.getResponse().statusCode()).isEqualTo(403);
    }

    @Test void sameUserExpiredPhysicalSessionCannotReceiveThroughItsFreshSibling() throws Exception {
        var fresh=connect("tenant-a"); subscribe(fresh,TENANT_A);
        var expired=connect("short-a");
        expired.send("SUBSCRIBE\nid:short\ndestination:/user/queue/inventory-alerts\nack:auto\n\n\u0000");
        await().atMost(WAIT).until(() -> sessions.subscribers(TENANT_A).size()==2);
        await().atMost(WAIT).until(() -> sessions.subscribers(TENANT_A).size()==1);
        var committed=event(TENANT_A,9);
        new TransactionTemplate(transactions).executeWithoutResult(s -> events.publishEvent(committed));
        assertThat(json.readTree(fresh.awaitFrame("MESSAGE").body()).path("resourceVersion").asLong()).isEqualTo(9);
        assertThat(expired.messages.poll(300,TimeUnit.MILLISECONDS)).isNull();
    }

    @Test void oneSocketReceivesInventoryAndOrderIndependentlyAndUnsubscribeDoesNotLoseInventory() throws Exception {
        var socket=connect("tenant-a"); subscribe(socket,TENANT_A);
        socket.send("SUBSCRIBE\nid:orders\ndestination:/user/queue/orders\nack:auto\n\n\u0000");
        await().atMost(WAIT).until(() -> sessions.subscribers(TENANT_A).stream().anyMatch(p -> sessions.subscribed(p,RealtimeChannel.ORDERS)));
        // Await actual broker registration, not only our inbound registry.
        var principal=sessions.subscribers(TENANT_A).getFirst();
        var headers=SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination("/queue/orders-user"+principal.sessionId());
        var check=MessageBuilder.createMessage(new byte[0],headers.getMessageHeaders());
        await().atMost(WAIT).untilAsserted(() -> assertThat(broker.getSubscriptionRegistry().findSubscriptions(check))
            .containsEntry(principal.sessionId(),List.of("orders")));
        var changed=new com.possaas.modules.order.event.OrderChanged(UUID.randomUUID(),TENANT_A,UUID.randomUUID(),2,
            Instant.now(),com.possaas.modules.order.entity.OrderStatus.OPEN,null,
            java.util.Set.of(com.possaas.modules.order.event.OrderChanged.Change.CREATED),List.of(),List.of(),false);
        new TransactionTemplate(transactions).executeWithoutResult(s -> events.publishEvent(changed));
        assertThat(socket.awaitFrame("MESSAGE").headers()).containsEntry("subscription","orders");
        socket.send("UNSUBSCRIBE\nid:orders\n\n\u0000");
        await().atMost(WAIT).until(() -> !sessions.subscribed(principal,RealtimeChannel.ORDERS));
        new TransactionTemplate(transactions).executeWithoutResult(s -> { events.publishEvent(changed); events.publishEvent(event(TENANT_A,8)); });
        assertThat(socket.awaitFrame("MESSAGE").headers()).containsEntry("subscription","inventory-alerts");
        assertThat(socket.messages.poll(300,TimeUnit.MILLISECONDS)).isNull();
    }

    private SocketClient raw() { return raw(null); }

    private SocketClient raw(String origin) {
        var listener = new SocketClient();
        var builder = client.newWebSocketBuilder().subprotocols("v12.stomp");
        if (origin != null) builder.header("Origin", origin);
        listener.socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener).join();
        sockets.add(listener);
        return listener;
    }

    private SocketClient connect(String token) throws Exception {
        var socket = raw();
        socket.send("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\nAuthorization:Bearer "
                + token + "\n\n\u0000");
        assertThat(socket.awaitFrame("CONNECTED").headers()).containsEntry("version", "1.2");
        return socket;
    }

    private void subscribe(SocketClient socket, UUID tenant) {
        socket.send("SUBSCRIBE\nid:inventory-alerts\ndestination:/user/queue/inventory-alerts\nack:auto\n\n\u0000");
        await().atMost(WAIT).untilAsserted(() -> {
            var recipients = sessions.subscribers(tenant);
            assertThat(recipients).hasSize(1);
            var principal = recipients.getFirst();
            assertThat(broker.getSubscriptionRegistry().findSubscriptions(subscriptionMessage(principal)))
                    .containsEntry(principal.sessionId(), List.of("inventory-alerts"));
        });
    }

    private org.springframework.messaging.Message<byte[]> subscriptionMessage(RealtimePrincipal principal) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination("/queue/inventory-alerts-user" + principal.sessionId());
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private InventoryAlertChangedEvent event(UUID tenant, long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("warehouseId", UUID.randomUUID());
        payload.put("ingredientId", UUID.randomUUID());
        payload.put("status", "OPEN");
        payload.put("resolutionReason", null);
        return new InventoryAlertChangedEvent(tenant, new RealtimeEventEnvelope(UUID.randomUUID(),
                "INVENTORY_LOW_STOCK_ALERT_CHANGED", 1, "LOW_STOCK_ALERT", UUID.randomUUID(), version,
                Instant.parse("2026-10-24T08:00:00Z"), payload));
    }

    private record Frame(String command, Map<String, String> headers, String body) {}

    private static final class SocketClient implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder(), pending = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        private final BlockingQueue<Frame> messages = new LinkedBlockingQueue<>();
        private final BlockingQueue<Frame> control = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private WebSocket socket;

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) { accept(text.toString()); text.setLength(0); }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            binary.writeBytes(bytes);
            if (last) { accept(binary.toString(StandardCharsets.UTF_8)); binary.reset(); }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket webSocket, Throwable error) { failure.set(error); }

        private void accept(String value) {
            pending.append(value.replace("\r\n", "\n"));
            int end;
            while ((end = pending.indexOf("\u0000")) >= 0) {
                String data = pending.substring(0, end).stripLeading();
                pending.delete(0, end + 1);
                int headerEnd = data.indexOf("\n\n");
                if (headerEnd < 0) continue;
                String[] lines = data.substring(0, headerEnd).split("\n");
                Map<String, String> headers = new LinkedHashMap<>();
                for (int i = 1; i < lines.length; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon >= 0) headers.put(lines[i].substring(0, colon), lines[i].substring(colon + 1));
                }
                var frame = new Frame(lines[0], headers, data.substring(headerEnd + 2));
                ("MESSAGE".equals(frame.command()) ? messages : control).add(frame);
            }
        }

        private void send(String frame) { socket.sendText(frame, true).join(); }
        private Frame awaitFrame(String command) throws Exception {
            var queue = "MESSAGE".equals(command) ? messages : control;
            long deadline = System.nanoTime() + WAIT.toNanos();
            Frame frame;
            while ((frame = queue.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) != null) {
                if (command.equals(frame.command())) return frame;
                assertThat(frame.command()).as("Unexpected STOMP response: %s", frame).isNotEqualTo("ERROR");
            }
            throw new AssertionError("Timed out awaiting STOMP " + command, failure.get());
        }
        private void close() { if (socket != null) socket.abort(); }
    }
}
