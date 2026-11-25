package com.possaas.modules.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.application.port.RestaurantQrOrderingLifecycle;
import com.possaas.modules.restaurant.dto.UpdateQrOrderingSettingsRequest;
import com.possaas.modules.restaurant.entity.QrOrderingEntry;
import com.possaas.modules.restaurant.entity.QrOrderingSettings;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.restaurant.service.RestaurantQrOrderingCommandService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RestaurantQrOrderingCommandServiceTest {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-24T03:00:00Z");
    private final CurrentTenantProvider tenant = mock(CurrentTenantProvider.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final RestaurantRepository restaurants = mock(RestaurantRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final RestaurantQrOrderingLifecycle lifecycle = mock(RestaurantQrOrderingLifecycle.class);
    private final RestaurantQrOrderingCommandService service = new RestaurantQrOrderingCommandService(
        tenant, users, restaurants, audit, lifecycle, Clock.fixed(now, ZoneOffset.UTC));
    private Restaurant restaurant;

    @BeforeEach void setUp() {
        restaurant = new Restaurant();
        restaurant.setId(tenantId);
        when(tenant.getRequiredRestaurantId()).thenReturn(tenantId);
        when(users.getRequired()).thenReturn(new CurrentUser(userId, tenantId, "o@x.vn", "OWNER", Set.of()));
        when(restaurants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(restaurant));
    }

    @Test @SuppressWarnings("unchecked") void switchingTableOffSavesAuditsAndWithdrawsOnlyTableGuests() {
        var response = service.update(new UpdateQrOrderingSettingsRequest(false, true), "1.2.3.4");

        assertThat(response.tableEnabled()).isFalse();
        assertThat(response.takeawayEnabled()).isTrue();
        assertThat(restaurant.qrOrdering()).isEqualTo(new QrOrderingSettings(false, true));
        verify(restaurants).saveAndFlush(restaurant);
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(tenantId), eq(userId), eq("RESTAURANT_QR_ORDERING_UPDATED"), eq("restaurants"),
            eq(tenantId), before.capture(), after.capture(), eq("1.2.3.4"));
        assertThat(before.getValue()).containsEntry("tableEnabled", true);
        assertThat(after.getValue()).containsEntry("tableEnabled", false).containsEntry("takeawayEnabled", true);
        verify(lifecycle).qrOrderingDisabled(tenantId, QrOrderingEntry.TABLE, userId, now, "1.2.3.4");
        verifyNoMoreInteractions(lifecycle);
    }

    @Test void switchingBothOffWithdrawsBothEntryPoints() {
        service.update(new UpdateQrOrderingSettingsRequest(false, false), "ip");
        verify(lifecycle).qrOrderingDisabled(tenantId, QrOrderingEntry.TABLE, userId, now, "ip");
        verify(lifecycle).qrOrderingDisabled(tenantId, QrOrderingEntry.TAKEAWAY, userId, now, "ip");
    }

    @Test void switchingBackOnWithdrawsNothing() {
        restaurant.changeQrOrdering(new QrOrderingSettings(false, false));
        var response = service.update(new UpdateQrOrderingSettingsRequest(true, false), "ip");
        assertThat(response.tableEnabled()).isTrue();
        verify(restaurants).saveAndFlush(restaurant);
        verifyNoInteractions(lifecycle);
    }

    @Test void sameSettingsAgainChangesNothing() {
        service.update(new UpdateQrOrderingSettingsRequest(true, true), "ip");
        verify(restaurants, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), anyString(), anyString(), any(), anyMap(), anyMap(), anyString());
        verifyNoInteractions(lifecycle);
    }
}
