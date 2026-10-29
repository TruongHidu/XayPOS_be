package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.order.application.port.ActiveOrderRecipeQuery;
import com.possaas.modules.order.application.port.OrderReferenceCostQuery;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.cost.*;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderRecipeSnapshotServiceTest {
    final OrderItemRecipeSnapshotRepository snapshots=mock(OrderItemRecipeSnapshotRepository.class);
    final OrderItemRecipeUsageRepository usages=mock(OrderItemRecipeUsageRepository.class);
    final ActiveOrderRecipeQuery recipes=mock(ActiveOrderRecipeQuery.class);
    final CurrentEntitlementQuery entitlements=mock(CurrentEntitlementQuery.class);
    final AuditService audit=mock(AuditService.class);
    final OrderReferenceCostQuery prices=mock(OrderReferenceCostQuery.class);
    final OrderRecipeSnapshotService service=new OrderRecipeSnapshotService(snapshots,usages,recipes,entitlements,
        new RecipeUsageQuantityCalculator(),new OrderRecipeSnapshotPolicy(),audit,
        new OrderCostSnapshotPreparation(prices,new OrderReferenceCostCalculator()));
    final Instant now=Instant.parse("2026-10-20T08:00:00Z");
    final UUID tenant=UUID.randomUUID(),orderId=UUID.randomUUID();
    final CurrentUser actor=new CurrentUser(UUID.randomUUID(),tenant,"test@example.com","WAITER",Set.of("ORDER_CREATE","ORDER_UPDATE"));
    @Test void repeatedDisabledMenuLinesUseOneDistinctConfigurationBatchAndNoDefinitionQueries() {
        var first=line();var second=line();second.setItemId(first.getItemId());
        var entitlement=mock(com.possaas.modules.subscription.domain.CurrentEntitlement.class);
        when(entitlements.getForRestaurant(tenant)).thenReturn(entitlement);
        when(entitlement.hasFeature("RECIPE_MANAGEMENT")).thenReturn(true);
        when(recipes.findRecipeSettings(tenant,List.of(first.getItemId()))).thenReturn(Map.of(first.getItemId(),false));
        when(prices.findManualCosts(tenant,List.of(first.getItemId()))).thenReturn(new OrderReferenceCostQuery.Prices("VND",
            Map.of(first.getItemId(),new OrderReferenceCostQuery.Price(first.getItemId(),"portion",BigDecimal.TEN,true))));
        service.capture(order(),List.of(first,second),actor,now,null);
        verify(recipes,times(1)).findRecipeSettings(tenant,List.of(first.getItemId()));
        verify(prices,times(1)).findManualCosts(tenant,List.of(first.getItemId()));
        verify(prices,never()).findIngredientCosts(any(),anyCollection());
        verify(recipes,never()).findActive(any(),anyCollection());verifyNoInteractions(usages);
        verify(snapshots).saveAllAndFlush(argThat((List<OrderItemRecipeSnapshot> rows)->rows.size()==2
            && rows.stream().allMatch(row->row.getSnapshotStatus()==RecipeSnapshotStatus.RECIPE_DISABLED
                && !row.getRecipeEnabledSnapshot() && row.getCostSourceSnapshot()==OrderCostSource.MANUAL
                && row.getCostSnapshotVersion()==1 && row.getCostStatus()==OrderCostStatus.COMPLETE
                && row.getTotalCostSnapshot().compareTo(new BigDecimal("30"))==0)));
        verify(audit).recordAll(argThat(events->events.size()==2&&events.stream().allMatch(event->
            event.actionCode().equals("ORDER_ITEM_RECIPE_SNAPSHOTTED")
                && event.afterData().get("costStatus")==OrderCostStatus.COMPLETE
                && ((BigDecimal)event.afterData().get("totalCostSnapshot")).compareTo(new BigDecimal("30"))==0)));
    }
    @Test void recordedMonetaryDecisionNeverReadsTodaysCostsOrFeaturesOnReplay() {
        var line=line();var header=new OrderItemRecipeSnapshot(line,RecipeSnapshotStatus.RECIPE_DISABLED,null,null,null,null,now,false,
            OrderCostSnapshot.complete(new BigDecimal("25"),new BigDecimal("75")));
        when(snapshots.findAllByRestaurantIdAndOrderItemIdIn(eq(tenant),anyCollection())).thenReturn(List.of(header));
        assertThat(service.capture(order(),List.of(line),actor,now.plusSeconds(60),null)).isNull();
        verifyNoInteractions(recipes,prices,entitlements,usages,audit);verify(snapshots,never()).saveAllAndFlush(any());
    }
    @Test void featureDisabledDecisionPrecedesLiveRecipeAndCostReadsAndIsAuditedAsUnavailable() {
        var line=line();var entitlement=mock(com.possaas.modules.subscription.domain.CurrentEntitlement.class);
        when(entitlements.getForRestaurant(tenant)).thenReturn(entitlement);
        when(recipes.findRecipeSettings(tenant,List.of(line.getItemId()))).thenReturn(Map.of(line.getItemId(),true));
        assertThat(service.capture(order(),List.of(line),actor,now,null)).isSameAs(entitlement);
        var sequence=inOrder(entitlements,recipes,snapshots,audit);
        sequence.verify(entitlements).getForRestaurant(tenant);sequence.verify(recipes).findRecipeSettings(tenant,List.of(line.getItemId()));
        sequence.verify(snapshots).saveAllAndFlush(argThat((List<OrderItemRecipeSnapshot> rows)->rows.size()==1
            &&rows.getFirst().getCostStatus()==OrderCostStatus.UNAVAILABLE&&rows.getFirst().getTotalCostSnapshot()==null));
        sequence.verify(audit).recordAll(argThat(events->events.size()==1&&events.getFirst().afterData().get("costStatus")==OrderCostStatus.UNAVAILABLE));
        verify(recipes,never()).findActive(any(),anyCollection());verifyNoInteractions(prices,usages);
    }
    @Test void recordedNoRecipeDecisionIsNeverRebuiltFromCurrentRecipesOrFeatures() {
        var line=line();var header=new OrderItemRecipeSnapshot(line,RecipeSnapshotStatus.NO_RECIPE,null,null,null,null,now,true,OrderCostSnapshot.legacy());
        when(snapshots.findAllByRestaurantIdAndOrderItemIdIn(eq(tenant),anyCollection())).thenReturn(List.of(header));
        service.capture(order(),List.of(line),actor,now,null);
        verifyNoInteractions(recipes,entitlements,usages,audit,prices);verify(snapshots,never()).saveAllAndFlush(any());
    }
    @Test void changedQuantityOnRecordedDecisionIsAConflictNotAnOverwrite() {
        var line=line();var header=new OrderItemRecipeSnapshot(line,RecipeSnapshotStatus.FEATURE_DISABLED,null,null,null,null,now,true,OrderCostSnapshot.unavailable());
        when(snapshots.findAllByRestaurantIdAndOrderItemIdIn(eq(tenant),anyCollection())).thenReturn(List.of(header));
        line.setQuantity(new BigDecimal("9"));
        assertThatThrownBy(()->service.capture(order(),List.of(line),actor,now,null)).isInstanceOfSatisfying(ConflictException.class,
            e->assertThat(e.getCode()).isEqualTo("ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT"));
        verifyNoInteractions(recipes,entitlements,usages,audit,prices);
    }
    @Test void submittedLineWithoutDurableDecisionIsNotCapturedUsingTodaysRecipe() {
        var line=line();line.setSentToKitchenAt(now);
        when(snapshots.findAllByRestaurantIdAndOrderItemIdIn(eq(tenant),anyCollection())).thenReturn(List.of());
        assertThatThrownBy(()->service.capture(order(),List.of(line),actor,now,null)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(recipes,entitlements,usages,audit,prices);
    }
    @Test void emptyAcceptanceNeverReadsAnyBusinessData() {
        service.capture(order(),List.of(),actor,now,null);verifyNoInteractions(snapshots,usages,recipes,entitlements,audit,prices);
    }
    private Order order(){var order=new Order();order.initialize(tenant,now);order.setId(orderId);order.setCurrencyCode("VND");return order;}
    private OrderItem line(){var line=new OrderItem();line.initialize(tenant,now);line.setId(UUID.randomUUID());line.setOrderId(orderId);line.setItemId(UUID.randomUUID());line.setUnit("portion");line.setQuantity(new BigDecimal("3"));return line;}
}
