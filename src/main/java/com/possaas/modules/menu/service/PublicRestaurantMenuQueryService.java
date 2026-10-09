package com.possaas.modules.menu.service;

import com.possaas.modules.menu.dto.*;
import com.possaas.modules.restaurant.service.PublicRestaurantMenuResolver;
import com.possaas.modules.subscription.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicRestaurantMenuQueryService {
    private final PublicRestaurantMenuResolver resolver;
    private final PublicMenuAccessPolicy access;
    private final PublicMenuReadService menu;
    private final PublicMenuResponseMapper mapper;

    public PublicRestaurantMenuResponse context(String token) {
        var context = resolver.resolve(token);
        access.requireAccess(context.restaurantId());
        return mapper.context(context, menu.groups(context.restaurantId()));
    }

    public PageResponse<PublicMenuItemResponse> items(String token, PublicMenuSearch search) {
        search.pageable();
        var context = resolver.resolve(token);
        access.requireAccess(context.restaurantId());
        return menu.items(context.restaurantId(), search);
    }
}
