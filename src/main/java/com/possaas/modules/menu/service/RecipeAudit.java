package com.possaas.modules.menu.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.menu.entity.Recipe;
import com.possaas.modules.menu.entity.RecipeItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecipeAudit {
    private final AuditService auditService;

    public Map<String, Object> snapshot(Recipe recipe, List<RecipeItem> lines) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("menuItemId", recipe.getMenuItemId());
        snapshot.put("recipeId", recipe.getId());
        snapshot.put("recipeVersion", recipe.getRecipeVersion());
        snapshot.put("yieldQuantity", recipe.getYieldQuantity());
        snapshot.put("yieldUnit", recipe.getYieldUnit());
        snapshot.put("active", recipe.isActive());
        snapshot.put("ingredients", lines.stream().map(this::line).toList());
        return snapshot;
    }

    public void record(CurrentUser actor, String action, Recipe recipe, Map<String, Object> before,
            Map<String, Object> after, String ipAddress) {
        auditService.record(actor.restaurantId(), actor.userId(), action, "recipes", recipe.getId(), before, after,
                ipAddress);
    }

    private Map<String, Object> line(RecipeItem item) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ingredientId", item.getIngredientId());
        line.put("sku", item.getIngredientSkuSnapshot());
        line.put("name", item.getIngredientNameSnapshot());
        line.put("unit", item.getIngredientUnitSnapshot());
        line.put("quantity", item.getQuantity());
        line.put("wastePercent", item.getWastePercent());
        return line;
    }
}
