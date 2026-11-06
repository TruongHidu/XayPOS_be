package com.possaas.infrastructure.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.event.OrderChanged;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.event.QrOrderRequestChanged;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketSession;

class RealtimeExtensionTest {
    final Instant now = Instant.parse("2026-10-25T00:00:00Z");
    final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), resource = UUID.randomUUID();
    final RealtimeDispatcher dispatcher = mock(RealtimeDispatcher.class);
    final OrderRealtimeAdapter adapter = new OrderRealtimeAdapter(dispatcher);

    OrderChanged order(boolean kitchen) {
        return new OrderChanged(UUID.randomUUID(),tenant,resource,7,now,OrderStatus.CONFIRMED,null,
                Set.of(OrderChanged.Change.ITEMS_ADDED),List.of(resource),kitchen ? List.of(resource) : List.of(),kitchen);
    }
    @Test void finalOrderSnapshotUsesOneEventIdForOrderAndKitchenFanoutWithoutPrivateDetails() {
        var event = order(true); adapter.changed(event);
        var wire = ArgumentCaptor.forClass(RealtimeEventEnvelope.class);
        verify(dispatcher).enqueue(eq(tenant),wire.capture(),eq(Set.of(RealtimeChannel.ORDERS,RealtimeChannel.KITCHEN)));
        assertThat(wire.getValue().eventId()).isEqualTo(event.eventId());
        assertThat(wire.getValue().resourceVersion()).isEqualTo(7);
        assertThat(wire.getValue().payload()).containsOnlyKeys("orderStatus","tableSessionId","changes","changedOrderItemIds","newKitchenItemIds");
        assertThat(wire.getValue().payload().get("newKitchenItemIds")).isEqualTo(List.of(resource));
    }
    @Test void draftOnlyInvalidatesOrderChannelAndPendingQrOnlyInvalidatesStaffRequests() {
        adapter.changed(order(false));
        verify(dispatcher).enqueue(eq(tenant),any(),eq(Set.of(RealtimeChannel.ORDERS)));
        adapter.changed(new QrOrderRequestChanged(UUID.randomUUID(),tenant,resource,0,now,QrRequestStatus.PENDING,
                QrOrderRequestChanged.Change.CREATED,QrEntryType.TABLE,UUID.randomUUID(),null,null,null));
        verify(dispatcher).enqueue(eq(tenant),any(),eq(Set.of(RealtimeChannel.QR_REQUESTS)));
        verifyNoMoreInteractions(dispatcher);
    }
    @Test void nestedPayloadAndBusinessCollectionsAreSnapshotsNotLiveReferences() {
        List<UUID> ids = new ArrayList<>(List.of(resource));
        var event = new OrderChanged(UUID.randomUUID(),tenant,resource,1,now,OrderStatus.OPEN,null,
                Set.of(OrderChanged.Change.CREATED),ids,List.of(),false);
        var nested = new LinkedHashMap<String,Object>(); nested.put("ids",ids); nested.put("nullable",null);
        var payload = new LinkedHashMap<String,Object>(); payload.put("nested",nested);
        var wire = new RealtimeEventEnvelope(UUID.randomUUID(),"TEST",1,"ORDER",resource,1,now,payload);
        ids.clear(); nested.clear(); payload.clear();
        assertThat(event.changedOrderItemIds()).containsExactly(resource);
        assertThat((Map<?,?>)wire.payload().get("nested")).hasSize(2);
        assertThatThrownBy(() -> event.changedOrderItemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> wire.payload().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>)((Map<?,?>)wire.payload().get("nested")).get("ids")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void executorRejectionAndAdapterFailureNeverEscapeAfterCommit() {
        var d = new RealtimeDispatcher(command -> { throw new RejectedExecutionException(); },
                mock(RealtimeSessions.class),mock(RealtimeAccess.class),mock(SimpMessagingTemplate.class),new ObjectMapper());
        var wire = new RealtimeEventEnvelope(UUID.randomUUID(),"ORDER_CHANGED",1,"ORDER",resource,0,now,Map.of());
        assertThatCode(() -> d.enqueue(tenant,wire,Set.of(RealtimeChannel.ORDERS))).doesNotThrowAnyException();
        doThrow(new IllegalStateException()).when(dispatcher).enqueue(any(),any(),anySet());
        assertThatCode(() -> adapter.changed(order(true))).doesNotThrowAnyException();
    }
    @Test void multipleSubscriptionsUnsubscribeIndependentlyAndRevocationFencesOnlyOneChannel() throws Exception {
        var scheduler = mock(TaskScheduler.class); when(scheduler.getClock()).thenReturn(Clock.fixed(now,ZoneOffset.UTC));
        var sessions = new RealtimeSessions(scheduler,10,4,4);
        var socket = mock(WebSocketSession.class); when(socket.getId()).thenReturn("s1"); when(socket.isOpen()).thenReturn(true);
        sessions.opened(socket);
        var p = new RealtimePrincipal("s1",user,tenant,"OWNER",now.plusSeconds(30)); sessions.authenticate(p);
        sessions.subscribe("s1","orders",RealtimeChannel.ORDERS);
        sessions.subscribe("s1","stock",RealtimeChannel.INVENTORY_ALERTS);
        assertThatThrownBy(() -> sessions.subscribe("s1","orders",RealtimeChannel.KITCHEN)).isInstanceOf(RealtimeRejected.class);
        assertThatThrownBy(() -> sessions.subscribe("s1","another",RealtimeChannel.ORDERS)).isInstanceOf(RealtimeRejected.class);
        sessions.fence("s1",RealtimeChannel.INVENTORY_ALERTS);
        assertThat(sessions.subscribed(p,RealtimeChannel.ORDERS)).isTrue();
        assertThat(sessions.subscribed(p,RealtimeChannel.INVENTORY_ALERTS)).isFalse();
        sessions.unsubscribe("s1","stock");
        assertThat(sessions.subscribed(p,RealtimeChannel.ORDERS)).isTrue();
        sessions.shutdown(); assertThat(sessions.subscribers(tenant)).isEmpty();
        verify(socket).close(any());
    }
    @Test void executorDequeueChecksExpiryEvenWhenTheTimerHasNotRunAndSiblingIsStillValid() throws Exception {
        var scheduler = mock(TaskScheduler.class); when(scheduler.getClock()).thenReturn(Clock.fixed(now,ZoneOffset.UTC));
        var sessions = new RealtimeSessions(scheduler,10,4,4);
        for (String id : List.of("old","fresh")) {
            var socket = mock(WebSocketSession.class); when(socket.getId()).thenReturn(id); when(socket.isOpen()).thenReturn(true);
            sessions.opened(socket); sessions.authenticate(new RealtimePrincipal(id,user,tenant,"OWNER",now.plusSeconds(id.equals("old")?1:100)));
            sessions.subscribe(id,"orders",RealtimeChannel.ORDERS);
        }
        var fence = new RealtimeOutboundInterceptor(sessions);
        var old = frame("old");
        assertThat(fence.preSend(old,mock(MessageChannel.class))).isSameAs(old);
        when(scheduler.getClock()).thenReturn(Clock.fixed(now.plusSeconds(2),ZoneOffset.UTC));
        assertThat(fence.beforeHandle(old,mock(MessageChannel.class),mock(MessageHandler.class))).isNull();
        var fresh = frame("fresh");
        assertThat(fence.beforeHandle(fresh,mock(MessageChannel.class),mock(MessageHandler.class))).isSameAs(fresh);
        sessions.removed("fresh");
        assertThat(fence.beforeHandle(fresh,mock(MessageChannel.class),mock(MessageHandler.class))).isNull();
    }
    private Message<byte[]> frame(String id) {
        var h = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        h.setSessionId(id); h.setSubscriptionId("orders"); h.setDestination("/queue/orders-user"+id);
        return MessageBuilder.createMessage(new byte[0],h.getMessageHeaders());
    }
}
