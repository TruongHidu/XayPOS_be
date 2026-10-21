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

    public void capture(Order order, List<OrderItem> accepted, CurrentUser actor, Instant now, String ip) {
        if (accepted.isEmpty()) return;
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
        if (fresh.isEmpty()) return;
        boolean enabled = entitlements.getForRestaurant(actor.restaurantId()).hasFeature("RECIPE_MANAGEMENT");
        var current = enabled ? recipes.findActive(actor.restaurantId(),
                fresh.stream().map(OrderItem::getItemId).distinct().toList()) : Map.<UUID, RecipeDefinition>of();
        List<OrderItemRecipeSnapshot> headers = new ArrayList<>();
        Map<UUID, List<Usage>> prepared = new HashMap<>();
        for (OrderItem line : fresh) {
            RecipeDefinition recipe = current.get(line.getItemId());
            var status = !enabled ? RecipeSnapshotStatus.FEATURE_DISABLED
                    : recipe == null ? RecipeSnapshotStatus.NO_RECIPE : RecipeSnapshotStatus.CAPTURED;
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
            headers.add(new OrderItemRecipeSnapshot(line, status, recipe == null ? null : recipe.id(),
                    recipe == null ? null : recipe.version(), recipe == null ? null : recipe.yieldQuantity(),
                    recipe == null ? null : recipe.yieldUnit(), now));
        }
        snapshots.saveAllAndFlush(headers);
        List<OrderItemRecipeUsage> details = new ArrayList<>();
        List<AuditRecordCommand> events = new ArrayList<>();
        for (var header : headers) {
            for (Usage usage : prepared.getOrDefault(header.getOrderItemId(), List.of())) {
                var ingredient = usage.ingredient();
                details.add(new OrderItemRecipeUsage(actor.restaurantId(), header.getId(), ingredient.id(),
                        ingredient.name(), ingredient.sku(), ingredient.unit(), ingredient.recipeQuantity(),
                        ingredient.wastePercent(), ingredient.trackInventory(), usage.quantity(), now));
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("orderId", order.getId()); after.put("orderItemId", header.getOrderItemId());
            after.put("snapshotStatus", header.getSnapshotStatus());
            after.put("recipeId", header.getRecipeId()); after.put("recipeVersion", header.getRecipeVersion());
            events.add(new AuditRecordCommand(actor.restaurantId(), actor.userId(), "ORDER_ITEM_RECIPE_SNAPSHOTTED",
                    "order_item_recipe_snapshots", header.getId(), null, after, ip));
        }
        if (!details.isEmpty()) usages.saveAllAndFlush(details);
        audit.recordAll(events);
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
