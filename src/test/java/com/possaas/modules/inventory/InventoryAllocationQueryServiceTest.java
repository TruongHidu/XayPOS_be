package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.application.port.OrderAllocationItemQuery;
import com.possaas.modules.inventory.dto.InventoryAllocationResponse;
import com.possaas.modules.inventory.repository.InventoryAllocationQueryRepository;
import com.possaas.modules.inventory.service.InventoryAccessService;
import com.possaas.modules.inventory.service.InventoryAllocationQueryService;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.LineState;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class InventoryAllocationQueryServiceTest {
    private static final Set<String> PERMISSIONS = Set.of("ORDER_VIEW", "RECIPE_VIEW", "INVENTORY_VIEW");
    private final UUID tenant = UUID.randomUUID(), orderId = UUID.randomUUID(), itemId = UUID.randomUUID();
    private final BigDecimal quantity = new BigDecimal("3.000");
    private final InventoryAccessService access = mock(InventoryAccessService.class);
    private final FeatureAccessChecker features = mock(FeatureAccessChecker.class);
    private final OrderAllocationItemQuery orderItems = mock(OrderAllocationItemQuery.class);
    private final InventoryAllocationQueryRepository queries = mock(InventoryAllocationQueryRepository.class);
    private final OrderInventoryAllocationCommand allocations = mock(OrderInventoryAllocationCommand.class);
    private final InventoryAllocationQueryService service = new InventoryAllocationQueryService(
            access, features, orderItems, queries, allocations);

    @BeforeEach void authorize() { when(access.require(false)).thenReturn(actor(PERMISSIONS)); }

    @ParameterizedTest @ValueSource(strings = {"ORDER_VIEW", "RECIPE_VIEW", "INVENTORY_VIEW"})
    void eachViewPermissionIsRequiredBeforeFeaturesOrRecipeDataAreRead(String missing) {
        var granted = new HashSet<>(PERMISSIONS); granted.remove(missing);
        when(access.require(false)).thenReturn(actor(granted));
        assertThatThrownBy(() -> service.find(orderId, itemId)).isInstanceOf(AccessDeniedException.class);
        verify(access).require(false);
        verifyNoInteractions(features, orderItems, queries, allocations);
    }

    @Test void inventoryAccessFailureStopsBeforeAnyOrderOrRecipeDataLookup() {
        var denied = new BusinessException(HttpStatus.FORBIDDEN, "FEATURE_NOT_ENTITLED", "Inventory is unavailable");
        when(access.require(false)).thenThrow(denied);
        assertThatThrownBy(() -> service.find(orderId, itemId)).isSameAs(denied);
        verifyNoInteractions(features, orderItems, queries, allocations);
    }

    @ParameterizedTest @ValueSource(strings = {"ORDER_MANAGEMENT", "RECIPE_MANAGEMENT"})
    void additionalFeatureChecksRunBeforeOwnedOrderItemLookup(String missing) {
        var denied = new BusinessException(HttpStatus.FORBIDDEN, "FEATURE_NOT_ENTITLED", "Feature unavailable");
        doThrow(denied).when(features).requireFeature(tenant, missing);
        assertThatThrownBy(() -> service.find(orderId, itemId)).isSameAs(denied);
        verify(features).requireFeature(tenant, "ORDER_MANAGEMENT");
        if (missing.equals("RECIPE_MANAGEMENT")) verify(features).requireFeature(tenant, "RECIPE_MANAGEMENT");
        else verify(features, never()).requireFeature(tenant, "RECIPE_MANAGEMENT");
        verifyNoInteractions(orderItems, queries, allocations);
    }

    @ParameterizedTest @ValueSource(strings = {"ORDER_NOT_FOUND", "ORDER_ITEM_NOT_FOUND"})
    void parentOrChildOwnershipFailureIsPropagatedBeforeAllocationReads(String code) {
        var missing = new ResourceNotFoundException(code, "Resource not found");
        when(orderItems.find(tenant, orderId, itemId)).thenThrow(missing);
        assertThatThrownBy(() -> service.find(orderId, itemId)).isSameAs(missing);
        verify(orderItems).find(tenant, orderId, itemId);
        verifyNoInteractions(queries, allocations);
    }

    @Test void unsentLineReturnsResponseOnlyNotSubmittedWithoutCreatingInventoryData() {
        var line = line(false, "PENDING");
        when(orderItems.find(tenant, orderId, itemId)).thenReturn(line);
        when(queries.find(tenant, orderId, itemId)).thenReturn(Optional.empty());

        var result = service.find(orderId, itemId);

        assertThat(result.orderId()).isEqualTo(orderId); assertThat(result.orderItemId()).isEqualTo(itemId);
        assertThat(result.status()).isEqualTo("NOT_SUBMITTED");
        assertThat(result.allocationId()).isNull(); assertThat(result.recipeSnapshotId()).isNull();
        assertThat(result.skipReason()).isNull(); assertThat(result.warehouseId()).isNull();
        assertThat(result.reservedAt()).isNull(); assertThat(result.consumedAt()).isNull();
        assertThat(result.releasedAt()).isNull(); assertThat(result.ingredients()).isEmpty();
        var sequence = inOrder(access, features, orderItems, allocations, queries);
        sequence.verify(access).require(false);
        sequence.verify(features).requireFeature(tenant, "ORDER_MANAGEMENT");
        sequence.verify(features).requireFeature(tenant, "RECIPE_MANAGEMENT");
        sequence.verify(orderItems).find(tenant, orderId, itemId);
        sequence.verify(allocations).validate(tenant, orderId, List.of(state(line)));
        sequence.verify(queries).find(tenant, orderId, itemId);
        verifyNoMoreInteractions(allocations);
    }

    @Test void storedAllocationIsReturnedAfterValidationWithDefensivelyCopiedImmutableDetails() {
        var line = line(true, "PENDING");
        when(orderItems.find(tenant, orderId, itemId)).thenReturn(line);
        var ingredient = new InventoryAllocationResponse.Ingredient(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "FLOUR", "Flour", "g", new BigDecimal("315.000"));
        var original = new ArrayList<>(List.of(ingredient));
        var stored = new InventoryAllocationResponse(UUID.randomUUID(), orderId, itemId, UUID.randomUUID(),
                "RESERVED", null, UUID.randomUUID(), Instant.parse("2026-10-20T08:00:00Z"),
                null, null, original);
        when(queries.find(tenant, orderId, itemId)).thenReturn(Optional.of(stored));
        original.clear();

        var result = service.find(orderId, itemId);

        assertThat(result).isSameAs(stored);
        assertThat(result.ingredients()).containsExactly(ingredient);
        assertThatThrownBy(() -> result.ingredients().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(allocations).validate(tenant, orderId, List.of(state(line)));
        verifyNoMoreInteractions(allocations);
    }

    @Test void submittedLineWithMissingHeaderIsConflictWithoutLateReservation() {
        var line = line(true, "PENDING");
        when(orderItems.find(tenant, orderId, itemId)).thenReturn(line);
        when(queries.find(tenant, orderId, itemId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.find(orderId, itemId)).isInstanceOfSatisfying(BusinessException.class,
                failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getCode()).isEqualTo("INVENTORY_ALLOCATION_NOT_FOUND");
                });
        verify(allocations).validate(tenant, orderId, List.of(state(line)));
        verifyNoMoreInteractions(allocations);
    }

    @Test void storedDataCannotHideAnAllocationValidationConflict() {
        var line = line(true, "SERVED");
        when(orderItems.find(tenant, orderId, itemId)).thenReturn(line);
        var conflict = new ConflictException("INVENTORY_ALLOCATION_CONFLICT", "Invalid history");
        doThrow(conflict).when(allocations).validate(tenant, orderId, List.of(state(line)));
        assertThatThrownBy(() -> service.find(orderId, itemId)).isSameAs(conflict);
        verifyNoInteractions(queries);
        verify(allocations).validate(tenant, orderId, List.of(state(line)));
        verifyNoMoreInteractions(allocations);
    }

    @Test void readUsesRepeatableReadToKeepSourceStateHistoryAndBalanceChecksInOneSnapshot() {
        var transaction = InventoryAllocationQueryService.class.getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private CurrentUser actor(Set<String> permissions) {
        return new CurrentUser(UUID.randomUUID(), tenant, "owner@example.test", "OWNER", Set.copyOf(permissions));
    }
    private OrderAllocationItemQuery.Item line(boolean submitted, String status) {
        return new OrderAllocationItemQuery.Item(orderId, itemId, status, submitted, quantity, "portion");
    }
    private LineState state(OrderAllocationItemQuery.Item line) {
        return new LineState(line.orderItemId(), line.status(), line.submitted(), line.quantity(), line.unit());
    }
}
