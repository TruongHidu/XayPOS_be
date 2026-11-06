package com.possaas.modules.menu.service;

import com.possaas.modules.menu.dto.IngredientResponse;
import com.possaas.modules.menu.dto.IngredientSearch;
import com.possaas.modules.menu.repository.IngredientSpecifications;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IngredientQueryService {
    private final IngredientAccessService access;
    private final ItemRepository items;
    private final IngredientResponseMapper mapper;

    public PageResponse<IngredientResponse> search(IngredientSearch search) {
        var actor = access.require(false);
        var page = items.findAll(IngredientSpecifications.ingredients(actor.restaurantId(), search), search.pageable());
        return new PageResponse<>(page.getContent().stream().map(mapper::map).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public IngredientResponse detail(UUID ingredientId) {
        var actor = access.require(false);
        return mapper.map(access.ingredient(actor.restaurantId(), ingredientId, false));
    }
}
