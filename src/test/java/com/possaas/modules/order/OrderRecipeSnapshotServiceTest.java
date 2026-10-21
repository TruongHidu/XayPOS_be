package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.order.application.port.ActiveOrderRecipeQuery;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.*;
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
    final OrderRecipeSnapshotService service=new OrderRecipeSnapshotService(snapshots,usages,recipes,entitlements,
        new RecipeUsageQuantityCalculator(),new OrderRecipeSnapshotPolicy(),audit);
    final Instant now=Instant.parse("2026-10-20T08:00:00Z");
    final UUID tenant=UUID.randomUUID(),orderId=UUID.randomUUID();
    final CurrentUser actor=new CurrentUser(UUID.randomUUID(),tenant,"test@example.com","WAITER",Set.of("ORDER_CREATE","ORDER_UPDATE"));
    @Test void recordedNoRecipeDecisionIsNeverRebuiltFromCurrentRecipesOrFeatures() {
        var line=line();var header=new OrderItemRecipeSnapshot(line,RecipeSnapshotStatus.NO_RECIPE,null,null,null,null,now);
        when(snapshots.findAllByRestaurantIdAndOrderItemIdIn(eq(tenant),anyCollection())).thenReturn(List.of(header));
        service.capture(order(),List.of(line),actor,now,null);
        verifyNoInteractions(recipes,entitlements,usages,audit);verify(snapshots,never()).saveAllAndFlush(any());
    }
    @Test void changedQuantityOnRecordedDecisionIsAConflictNotAnOverwrite() {
        var line=line();var header=new OrderItemRecipeSnapshot(line,RecipeSnapshotStatus.FEATURE_DISABLED,null,null,null,null,now);
        when(snapshots.findAllByRestaurantIdAndOrderItemIdIn(eq(tenant),anyCollection())).thenReturn(List.of(header));
        line.setQuantity(new BigDecimal("9"));
        assertThatThrownBy(()->service.capture(order(),List.of(line),actor,now,null)).isInstanceOfSatisfying(ConflictException.class,
            e->assertThat(e.getCode()).isEqualTo("ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT"));
        verifyNoInteractions(recipes,entitlements,usages,audit);
    }
    @Test void submittedLineWithoutDurableDecisionIsNotCapturedUsingTodaysRecipe() {
        var line=line();line.setSentToKitchenAt(now);
        when(snapshots.findAllByRestaurantIdAndOrderItemIdIn(eq(tenant),anyCollection())).thenReturn(List.of());
        assertThatThrownBy(()->service.capture(order(),List.of(line),actor,now,null)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(recipes,entitlements,usages,audit);
    }
    @Test void emptyAcceptanceNeverReadsAnyBusinessData() {
        service.capture(order(),List.of(),actor,now,null);verifyNoInteractions(snapshots,usages,recipes,entitlements,audit);
    }
    private Order order(){var order=new Order();order.initialize(tenant,now);order.setId(orderId);return order;}
    private OrderItem line(){var line=new OrderItem();line.initialize(tenant,now);line.setId(UUID.randomUUID());line.setOrderId(orderId);line.setItemId(UUID.randomUUID());line.setUnit("portion");line.setQuantity(new BigDecimal("3"));return line;}
}
