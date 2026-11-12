package com.possaas.modules.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.dto.UpdateTransferAccountRequest;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.entity.TransferAccount;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.restaurant.service.RestaurantTransferAccountCommandService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RestaurantTransferAccountCommandServiceTest {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final CurrentTenantProvider tenant = mock(CurrentTenantProvider.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final RestaurantRepository restaurants = mock(RestaurantRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final RestaurantTransferAccountCommandService service =
        new RestaurantTransferAccountCommandService(tenant, users, restaurants, audit);
    private Restaurant restaurant;

    @BeforeEach void setUp() {
        restaurant = new Restaurant();
        restaurant.setId(tenantId);
        when(tenant.getRequiredRestaurantId()).thenReturn(tenantId);
        when(users.getRequired()).thenReturn(new CurrentUser(userId, tenantId, "o@x.vn", "OWNER", Set.of()));
        when(restaurants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(restaurant));
    }

    @Test @SuppressWarnings("unchecked") void savesNormalisedAccountAndAuditsWithoutTheFullNumber() {
        var response = service.update(new UpdateTransferAccountRequest("970436", "0123456789", "Quán Ăn Ngon"), "1.2.3.4");

        assertThat(response.configured()).isTrue();
        assertThat(response.accountName()).isEqualTo("QUAN AN NGON");
        assertThat(restaurant.transferAccount()).isPresent();
        verify(restaurants).saveAndFlush(restaurant);
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(tenantId), eq(userId), eq("RESTAURANT_TRANSFER_ACCOUNT_UPDATED"), eq("restaurants"),
            eq(tenantId), before.capture(), after.capture(), eq("1.2.3.4"));
        assertThat(before.getValue()).containsEntry("configured", false);
        assertThat(after.getValue()).containsEntry("accountNumber", "••••6789");
        assertThat(after.getValue().toString()).doesNotContain("0123456789");
    }

    @Test void sameAccountAgainChangesNothing() {
        restaurant.changeTransferAccount(TransferAccount.of("970436", "0123456789", "QUAN AN"));
        service.update(new UpdateTransferAccountRequest("970436", "0123456789", "quan an"), "ip");
        verify(restaurants, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), anyString(), anyString(), any(), anyMap(), anyMap(), anyString());
    }

    @Test void clearingRemovesTheWholeAccount() {
        restaurant.changeTransferAccount(TransferAccount.of("970436", "0123456789", "QUAN AN"));
        var response = service.clear("ip");
        assertThat(response.configured()).isFalse();
        assertThat(response.accountNumber()).isNull();
        assertThat(restaurant.transferAccount()).isEmpty();
        verify(audit).record(eq(tenantId), eq(userId), eq("RESTAURANT_TRANSFER_ACCOUNT_CLEARED"), eq("restaurants"),
            eq(tenantId), anyMap(), anyMap(), eq("ip"));
    }
}
