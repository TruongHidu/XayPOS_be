package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.inventory.application.port.TrackedIngredientCatalog;
import com.possaas.modules.inventory.dto.InventoryBalanceResponse;
import com.possaas.modules.inventory.dto.InventoryBalanceSearch;
import com.possaas.modules.inventory.repository.InventoryBalanceQueryRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryBalanceQueryService {
    private final InventoryAccessService access;
    private final TrackedIngredientCatalog ingredients;
    private final InventoryBalanceQueryRepository queries;

    public PageResponse<InventoryBalanceResponse> list(UUID warehouseId, InventoryBalanceSearch search) {
        var actor = access.require(false);
        access.warehouse(actor.restaurantId(), warehouseId);
        var result = queries.list(actor.restaurantId(), warehouseId, search);
        return page(result, search);
    }

    public InventoryBalanceResponse detail(UUID warehouseId, UUID ingredientId) {
        var actor = access.require(false);
        access.warehouse(actor.restaurantId(), warehouseId);
        ingredients.requireTracked(actor.restaurantId(), ingredientId);
        return queries.detail(actor.restaurantId(), warehouseId, ingredientId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TRACKED_INGREDIENT_NOT_FOUND", "Tracked ingredient not found"));
    }

    public PageResponse<InventoryBalanceResponse> lowStock(InventoryBalanceSearch search) {
        var actor = access.requireLowStock();
        if (search.warehouseId() != null)
            access.warehouse(actor.restaurantId(), search.warehouseId());
        var result = queries.lowStock(actor.restaurantId(), search);
        return page(result, search);
    }

    private static PageResponse<InventoryBalanceResponse> page(
            InventoryBalanceQueryRepository.PageResult result, InventoryBalanceSearch search) {
        long total = result.totalElements();
        int pages = total == 0 ? 0 : (int) ((total + search.size() - 1) / search.size());
        return new PageResponse<>(result.content(), search.page(), search.size(), total, pages);
    }
}
