package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.possaas.common.exception.*;
import com.possaas.common.security.*;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import com.possaas.modules.subscription.domain.CurrentEntitlement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

class OrderCostSnapshotQueryServiceTest {
    final UUID tenant=UUID.randomUUID(),orderId=UUID.randomUUID(),lineId=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-27T08:00:00Z");
    final CurrentTenantProvider tenants=mock(CurrentTenantProvider.class);
    final CurrentUserProvider users=mock(CurrentUserProvider.class);
    final CurrentEntitlementQuery entitlements=mock(CurrentEntitlementQuery.class);
    final CurrentEntitlement entitlement=mock(CurrentEntitlement.class);
    final OrderRepository orders=mock(OrderRepository.class);
    final OrderItemRepository lines=mock(OrderItemRepository.class);
    final OrderItemRecipeSnapshotRepository snapshots=mock(OrderItemRecipeSnapshotRepository.class);
    final OrderCostSnapshotQueryService service=new OrderCostSnapshotQueryService(new OrderCostSnapshotAccess(tenants,users,entitlements),orders,lines,snapshots,new OrderRecipeSnapshotPolicy());

    @BeforeEach void access(){
        when(tenants.getRequiredRestaurantId()).thenReturn(tenant);
        when(users.getRequired()).thenReturn(actor(tenant,Set.of("ORDER_VIEW","REPORT_DETAIL_VIEW")));
        when(entitlements.getForRestaurant(tenant)).thenReturn(entitlement);
        when(entitlement.hasFeature("ORDER_MANAGEMENT")).thenReturn(true);when(entitlement.hasFeature("DETAIL_REPORT")).thenReturn(true);
    }

    @ParameterizedTest @ValueSource(strings={"ORDER_VIEW","REPORT_DETAIL_VIEW"})
    void deniedPermissionsStopBeforeEntitlementOrOrderDataReads(String missing) {
        var permissions=new HashSet<>(Set.of("ORDER_VIEW","REPORT_DETAIL_VIEW"));permissions.remove(missing);
        when(users.getRequired()).thenReturn(actor(tenant,permissions));
        assertThatThrownBy(()->service.find(orderId,lineId)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(entitlements,orders,lines,snapshots);
    }

    @ParameterizedTest @ValueSource(strings={"ORDER_MANAGEMENT","DETAIL_REPORT"})
    void deniedFeaturesStopBeforeOrderDataReadsUsingOneEntitlementSnapshot(String missing) {
        when(entitlement.hasFeature(missing)).thenReturn(false);
        assertThatThrownBy(()->service.find(orderId,lineId)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("FEATURE_NOT_ENTITLED"));
        verify(entitlements).getForRestaurant(tenant);verifyNoMoreInteractions(entitlements);verifyNoInteractions(orders,lines,snapshots);
    }

    @Test void actorTenantMismatchStopsBeforeAnyBusinessDataReads() {
        when(users.getRequired()).thenReturn(actor(UUID.randomUUID(),Set.of("ORDER_VIEW","REPORT_DETAIL_VIEW")));
        assertThatThrownBy(()->service.find(orderId,lineId)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(entitlements,orders,lines,snapshots);
    }

    @Test void draftReadReturnsNotSubmittedWithoutCreatingAnySnapshot() {
        var line=fixture();var result=service.find(orderId,lineId);
        assertThat(result.costStatus()).isEqualTo(OrderCostStatus.NOT_SUBMITTED);assertThat(result.costSnapshotVersion()).isNull();
        assertThat(result.costSource()).isNull();assertThat(result.costBasis()).isNull();assertThat(result.costCapturedAt()).isNull();
        assertThat(result.unitCost()).isNull();assertThat(result.totalCost()).isNull();assertThat(result.orderQuantity()).isEqualByComparingTo(line.getQuantity());
        assertThat(result.currencyCode()).isEqualTo("VND");verify(snapshots,never()).saveAllAndFlush(any());
        verify(entitlement,never()).hasFeature("RECIPE_MANAGEMENT");verify(entitlement,never()).hasFeature("INVENTORY_MANAGEMENT");
    }

    @Test void submittedLineWithoutHistoricalHeaderIsConflictAndNeverRepaired() {
        var line=fixture();line.setSentToKitchenAt(now);
        OrderReferenceCostCalculatorTest.assertCode(()->service.find(orderId,lineId),"ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT");
        verify(snapshots,never()).saveAllAndFlush(any());
    }

    @Test void legacyHeaderKeepsStoredSourceButHasNoHistoricalMoneyOrCaptureTimestamp() {
        var line=fixture();line.setSentToKitchenAt(now);
        var legacy=new OrderItemRecipeSnapshot(line,RecipeSnapshotStatus.CAPTURED,UUID.randomUUID(),1,BigDecimal.ONE,"portion",now,true,OrderCostSnapshot.legacy());
        when(snapshots.findByRestaurantIdAndOrderItemId(tenant,lineId)).thenReturn(Optional.of(legacy));
        var result=service.find(orderId,lineId);
        assertThat(result.costStatus()).isEqualTo(OrderCostStatus.LEGACY_UNAVAILABLE);assertThat(result.costSource()).isEqualTo(OrderCostSource.RECIPE);
        assertThat(result.costSnapshotVersion()).isNull();assertThat(result.costCapturedAt()).isNull();assertThat(result.unitCost()).isNull();assertThat(result.totalCost()).isNull();
        verify(snapshots,never()).saveAllAndFlush(any());
    }

    @Test void knownSnapshotUsesFrozenMoneyAndCapturedTimeWithOnlySummaryAccess() {
        var line=fixture();line.setSentToKitchenAt(now);
        var header=new OrderItemRecipeSnapshot(line,RecipeSnapshotStatus.RECIPE_DISABLED,null,null,null,null,now,false,OrderCostSnapshot.complete(new BigDecimal("25.15"),new BigDecimal("75.45")));
        when(snapshots.findByRestaurantIdAndOrderItemId(tenant,lineId)).thenReturn(Optional.of(header));
        var result=service.find(orderId,lineId);
        assertThat(result.costStatus()).isEqualTo(OrderCostStatus.COMPLETE);assertThat(result.costSource()).isEqualTo(OrderCostSource.MANUAL);
        assertThat(result.costSnapshotVersion()).isEqualTo((short)1);assertThat(result.costBasis()).isEqualTo("CATALOG_REFERENCE_AT_ACCEPTANCE");
        assertThat(result.unitCost()).isEqualByComparingTo("25.15");assertThat(result.totalCost()).isEqualByComparingTo("75.45");assertThat(result.costCapturedAt()).isEqualTo(now);
        verify(entitlements,times(1)).getForRestaurant(tenant);verify(entitlement,never()).hasFeature("RECIPE_MANAGEMENT");verify(entitlement,never()).hasFeature("INVENTORY_MANAGEMENT");
        verify(snapshots,never()).saveAllAndFlush(any());
    }

    @Test void foreignOrderAndWrongOrderItemUseTenantAndOrderFilteredLookups() {
        when(orders.findByIdAndRestaurantId(orderId,tenant)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.find(orderId,lineId)).isInstanceOf(ResourceNotFoundException.class);verifyNoInteractions(lines,snapshots);
        fixture();when(lines.findByIdAndRestaurantIdAndOrderId(lineId,tenant,orderId)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.find(orderId,lineId)).isInstanceOf(ResourceNotFoundException.class);verifyNoInteractions(snapshots);
    }

    OrderItem fixture(){
        var order=new Order();order.initialize(tenant,now);order.setId(orderId);order.setCurrencyCode("VND");
        var line=new OrderItem();line.initialize(tenant,now);line.setId(lineId);line.setOrderId(orderId);line.setItemId(UUID.randomUUID());line.setUnit("portion");line.setQuantity(new BigDecimal("3"));
        when(orders.findByIdAndRestaurantId(orderId,tenant)).thenReturn(Optional.of(order));
        when(lines.findByIdAndRestaurantIdAndOrderId(lineId,tenant,orderId)).thenReturn(Optional.of(line));return line;
    }
    CurrentUser actor(UUID restaurant,Set<String> permissions){return new CurrentUser(UUID.randomUUID(),restaurant,"staff@example.test","WAITER",permissions);}
}
