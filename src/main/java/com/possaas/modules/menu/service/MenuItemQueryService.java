package com.possaas.modules.menu.service;

import com.possaas.modules.menu.dto.*;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuItemQueryService {
    private final MenuAccessService access;
    private final ItemRepository items;
    private final MenuResponseMapper mapper;

    public PageResponse<MenuItemResponse> search(MenuSearch search) {
        var actor = access.require(false);
        var page = items.findAll(MenuSpecifications.items(actor.restaurantId(), search), search.pageable(true));
        return new PageResponse<>(mapper.items(actor.restaurantId(), page.getContent()), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public MenuItemResponse detail(UUID id) {
        return mapper.item(access.item(access.require(false).restaurantId(), id, false));
    }
}
