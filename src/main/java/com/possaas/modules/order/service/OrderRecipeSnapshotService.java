package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditRecordCommand;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.order.application.port.ActiveOrderRecipeQuery;
import com.possaas.modules.order.application.port.ActiveOrderRecipeQuery.RecipeDefinition;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.OrderItemRecipeSnapshotRepository;
import com.possaas.modules.order.repository.OrderItemRecipeUsageRepository;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import com.possaas.modules.subscription.domain.CurrentEntitlement;
import com.possaas.modules.order.service.cost.OrderCostSnapshotPreparation;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Synchronous acceptance side effect, called under the existing restaurant mutation lock. */
@Service @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class OrderRecipeSnapshotService {
    private final OrderItemRecipeSnapshotRepository snapshots;
    private final OrderItemRecipeUsageRepository usages;
    private final ActiveOrderRecipeQuery recipes;
    private final CurrentEntitlementQuery entitlements;
    private final RecipeUsageQuantityCalculator calculator;
    private final OrderRecipeSnapshotPolicy policy;
    private final AuditService audit;
    private final OrderCostSnapshotPreparation costs;

    public CurrentEntitlement capture(Order order, List<OrderItem> accepted, CurrentUser actor, Instant now, String ip) {
        if (accepted.isEmpty()) return null;
        if (!order.getRestaurantId().equals(actor.restaurantId())) throw policy.conflict();
        for (OrderItem line : accepted) {
            if (line.getId() == null || !actor.restaurantId().equals(line.getRestaurantId())
                    || !order.getId().equals(line.getOrderId())) throw policy.conflict();
        }
        var existing = snapshots.findAllByRestaurantIdAndOrderItemIdIn(actor.restaurantId(),
                accepted.stream().map(OrderItem::getId).toList()).stream()
                .collect(Collectors.toMap(OrderItemRecipeSnapshot::getOrderItemId, Function.identity()));
        List<OrderItem> fresh = new ArrayList<>();
        for (OrderItem line : accepted) {
            var recorded = existing.get(line.getId());
            if (recorded != null) policy.requireMatches(recorded, line);
            else if (line.getSentToKitchenAt() != null) throw policy.conflict();
            else if (line.getStatus() == OrderItemStatus.PENDING) fresh.add(line);
        }
        if (fresh.isEmpty()) return null;
        // One acceptance entitlement supplies recipe and subsequent inventory decisions.
        var entitlement=entitlements.getForRestaurant(actor.restaurantId());
        boolean enabled=entitlement.hasFeature("RECIPE_MANAGEMENT");
        var menuIds = fresh.stream().map(OrderItem::getItemId).distinct().toList();
        var settings = recipes.findRecipeSettings(actor.restaurantId(), menuIds);
        if (settings==null || !settings.keySet().containsAll(menuIds)
                || menuIds.stream().anyMatch(id->settings.get(id)==null)) throw policy.conflict();
        var appliedIds = menuIds.stream().filter(id -> Boolean.TRUE.equals(settings.get(id))).toList();
        var current = enabled && !appliedIds.isEmpty() ? recipes.findActive(actor.restaurantId(), appliedIds)
                : Map.<UUID, RecipeDefinition>of();
        if(current==null) throw invalid();
        List<OrderItemRecipeSnapshot> headers = new ArrayList<>();
        Map<UUID,RecipeSnapshotStatus> statuses=new HashMap<>();
        Map<UUID, List<Usage>> prepared = new HashMap<>();
        for (OrderItem line : fresh) {
            RecipeDefinition recipe = current.get(line.getItemId());
            boolean requested = settings.get(line.getItemId());
            if (requested && enabled && recipe == null)
                throw new ConflictException("ACTIVE_RECIPE_REQUIRED", "An enabled menu item requires an active recipe");
            var status = !requested ? RecipeSnapshotStatus.RECIPE_DISABLED
                    : !enabled ? RecipeSnapshotStatus.FEATURE_DISABLED : RecipeSnapshotStatus.CAPTURED;
            if (recipe != null) {
                requireRecipe(recipe, line);
                var seen = new HashSet<UUID>();
                var quantities = recipe.ingredients().stream().map(ingredient -> {
                    if (ingredient.id() == null || !seen.add(ingredient.id()) || blank(ingredient.name())
                            || blank(ingredient.unit())) throw invalid();
                    return new Usage(ingredient, calculator.calculate(ingredient.recipeQuantity(),
                            ingredient.wastePercent(), line.getQuantity(), recipe.yieldQuantity()));
                }).toList();
                prepared.put(line.getId(), quantities);
            }
            statuses.put(line.getId(),status);
        }
        var money=costs.prepare(order,fresh.stream().map(line->new OrderCostSnapshotPreparation.Input(
            line.getId(),line.getItemId(),line.getUnit(),line.getQuantity(),statuses.get(line.getId()),
            prepared.getOrDefault(line.getId(),List.of()).stream().map(usage->new OrderCostSnapshotPreparation.Usage(
                usage.ingredient().id(),usage.ingredient().unit(),usage.quantity())).toList())).toList());
        for(var line:fresh) {
            var recipe=current.get(line.getItemId());
            headers.add(new OrderItemRecipeSnapshot(line,statuses.get(line.getId()),recipe==null?null:recipe.id(),
                recipe==null?null:recipe.version(),recipe==null?null:recipe.yieldQuantity(),
                recipe==null?null:recipe.yieldUnit(),now,settings.get(line.getItemId()),money.get(line.getId()).summary()));
        }
        snapshots.saveAllAndFlush(headers);
        List<OrderItemRecipeUsage> details = new ArrayList<>();
        List<AuditRecordCommand> events = new ArrayList<>();
        for (var header : headers) {
            for (Usage usage : prepared.getOrDefault(header.getOrderItemId(), List.of())) {
                var ingredient = usage.ingredient();
                details.add(new OrderItemRecipeUsage(actor.restaurantId(), header.getId(), ingredient.id(),
                        ingredient.name(), ingredient.sku(), ingredient.unit(), ingredient.recipeQuantity(),
                        ingredient.wastePercent(), ingredient.trackInventory(), usage.quantity(), now,
                        money.get(header.getOrderItemId()).ingredients().get(ingredient.id())));
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("orderId", order.getId()); after.put("orderItemId", header.getOrderItemId());
            after.put("snapshotStatus", header.getSnapshotStatus());
            after.put("recipeEnabledSnapshot", header.getRecipeEnabledSnapshot());
            after.put("costSourceSnapshot", header.getCostSourceSnapshot());
            after.put("costSnapshotVersion",header.getCostSnapshotVersion());
            after.put("costStatus",header.getCostStatus());
            after.put("costUnavailableReason",header.getCostUnavailableReason());
            after.put("unitCostSnapshot",header.getUnitCostSnapshot());
            after.put("totalCostSnapshot",header.getTotalCostSnapshot());
            after.put("recipeId", header.getRecipeId()); after.put("recipeVersion", header.getRecipeVersion());
            events.add(new AuditRecordCommand(actor.restaurantId(), actor.userId(), "ORDER_ITEM_RECIPE_SNAPSHOTTED",
                    "order_item_recipe_snapshots", header.getId(), null, after, ip));
        }
        if (!details.isEmpty()) usages.saveAllAndFlush(details);
        audit.recordAll(events);
        return entitlement;
    }

    private void requireRecipe(RecipeDefinition recipe, OrderItem line) {
        if (recipe.id() == null || !line.getItemId().equals(recipe.menuItemId()) || recipe.version() <= 0
                || recipe.yieldQuantity() == null || recipe.yieldQuantity().signum() <= 0
                || recipe.ingredients().isEmpty() || blank(recipe.yieldUnit())) throw invalid();
        if (!line.getUnit().equals(recipe.yieldUnit()))
            throw new ConflictException("RECIPE_YIELD_UNIT_MISMATCH", "Recipe yield unit differs from the submitted order unit");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ConflictException invalid() {
        return new ConflictException("INVALID_RECIPE_SNAPSHOT_DATA", "Active recipe data is invalid");
    }
    private record Usage(ActiveOrderRecipeQuery.IngredientDefinition ingredient, java.math.BigDecimal quantity) {}
}
