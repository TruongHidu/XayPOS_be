package com.possaas.modules.menu.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.menu.dto.IngredientRequests;
import com.possaas.modules.menu.dto.IngredientResponse;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.application.port.InventoryStockUsageQuery;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.menu.service.strategy.ItemCreationContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class IngredientCommandService {
    private final IngredientAccessService access;
    private final ItemRepository items;
    private final MenuItemFactory factory;
    private final MenuItemValidationPolicy validation;
    private final IngredientLifecyclePolicy lifecycle;
    private final RecipeDependencyPolicy recipeDependencies;
    private final InventoryStockUsageQuery inventoryStock;
    private final IngredientResponseMapper mapper;
    private final IngredientAudit audit;
    private final Clock clock;

    public IngredientResponse create(IngredientRequests.Create request, String ipAddress) {
        CurrentUser actor = access.require(true);
        var context = new ItemCreationContext(actor.restaurantId(), null, validation.sku(request.sku()),
                validation.required(request.name(), 150), validation.required(request.baseUnit(), 30),
                validation.optional(request.description()), null, BigDecimal.ZERO,
                validation.price(request.costPrice() == null ? BigDecimal.ZERO : request.costPrice()),
                request.trackInventory() != null && request.trackInventory(),
                request.active() == null || request.active(), clock.instant());
        Item ingredient = factory.create(ItemType.INGREDIENT, context);
        items.saveAndFlush(ingredient);
        audit.record(actor, "INGREDIENT_CREATED", ingredient, null, audit.snapshot(ingredient), ipAddress);
        return mapper.map(ingredient);
    }

    public IngredientResponse update(UUID ingredientId, IngredientRequests.Update request, String ipAddress) {
        CurrentUser actor = access.require(true);
        Item ingredient = access.ingredient(actor.restaurantId(), ingredientId, false);
        if (request.sku() == null && request.name() == null && request.baseUnit() == null
                && request.description() == null && request.costPrice() == null && request.trackInventory() == null)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_UPDATE_REQUEST", "No fields provided");
        lifecycle.requireVersion(ingredient, request.expectedVersion());
        Map<String, Object> before = audit.snapshot(ingredient);
        if (request.sku() != null)
            ingredient.setSku(validation.sku(request.sku()));
        if (request.name() != null)
            ingredient.setName(validation.required(request.name(), 150));
        if (request.baseUnit() != null) {
            String baseUnit = validation.required(request.baseUnit(), 30);
            if (!Objects.equals(ingredient.getBaseUnit(), baseUnit)) {
                requireNoReservations(actor.restaurantId(), ingredientId);
                recipeDependencies.requireIngredientUnitChangeAllowed(actor.restaurantId(), ingredientId);
                requireNoStock(actor.restaurantId(), ingredientId);
            }
            ingredient.setBaseUnit(baseUnit);
        }
        if (request.description() != null)
            ingredient.setDescription(validation.optional(request.description()));
        if (request.costPrice() != null)
            ingredient.setCostPrice(validation.price(request.costPrice()));
        if (Boolean.FALSE.equals(request.trackInventory()) && ingredient.isTrackInventory()) {
            requireNoReservations(actor.restaurantId(), ingredientId);
            requireNoStock(actor.restaurantId(), ingredientId);
        }
        if (request.trackInventory() != null)
            ingredient.setTrackInventory(request.trackInventory());
        if (!before.equals(audit.snapshot(ingredient)))
            persist(actor, ingredient, "INGREDIENT_UPDATED", before, ipAddress);
        return mapper.map(ingredient);
    }

    public IngredientResponse status(UUID ingredientId, IngredientRequests.Status request, String ipAddress) {
        CurrentUser actor = access.require(true);
        Item ingredient = access.ingredient(actor.restaurantId(), ingredientId, false);
        if (ingredient.isActive() == request.active())
            return mapper.map(ingredient);
        lifecycle.requireVersion(ingredient, request.expectedVersion());
        if (!request.active()) {
            requireNoReservations(actor.restaurantId(), ingredientId);
            recipeDependencies.requireIngredientNotUsed(actor.restaurantId(), ingredientId);
        }
        Map<String, Object> before = audit.snapshot(ingredient);
        lifecycle.changeActive(ingredient, request.active());
        persist(actor, ingredient, request.active() ? "INGREDIENT_ENABLED" : "INGREDIENT_DISABLED", before,
                ipAddress);
        return mapper.map(ingredient);
    }

    public void delete(UUID ingredientId, long expectedVersion, String ipAddress) {
        CurrentUser actor = access.require(true);
        Item ingredient = access.ingredient(actor.restaurantId(), ingredientId, true);
        if (ingredient.getDeletedAt() != null)
            return;
        lifecycle.requireVersion(ingredient, expectedVersion);
        requireNoReservations(actor.restaurantId(), ingredientId);
        recipeDependencies.requireIngredientNotUsed(actor.restaurantId(), ingredientId);
        requireNoStock(actor.restaurantId(), ingredientId);
        Map<String, Object> before = audit.snapshot(ingredient);
        lifecycle.softDelete(ingredient, clock.instant());
        persist(actor, ingredient, "INGREDIENT_DELETED", before, ipAddress);
    }

    private void persist(CurrentUser actor, Item ingredient, String action, Map<String, Object> before,
            String ipAddress) {
        ingredient.setUpdatedAt(clock.instant());
        items.saveAndFlush(ingredient);
        audit.record(actor, action, ingredient, before, audit.snapshot(ingredient), ipAddress);
    }

    private void requireNoStock(UUID restaurantId, UUID ingredientId) {
        if (inventoryStock.hasNonZeroStock(restaurantId, ingredientId))
            throw new BusinessException(HttpStatus.CONFLICT, "INGREDIENT_HAS_STOCK",
                    "Ingredient still has inventory in at least one warehouse");
    }

    private void requireNoReservations(UUID restaurantId, UUID ingredientId) {
        if (inventoryStock.hasReservations(restaurantId, ingredientId))
            throw new BusinessException(HttpStatus.CONFLICT, "INGREDIENT_HAS_RESERVATIONS",
                    "Ingredient has reservations for submitted order items");
    }
}
