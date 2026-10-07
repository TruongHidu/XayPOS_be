package com.possaas.modules.menu.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.menu.dto.*;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.table.service.PublicTableQrResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicQrMenuQueryService {
    private final PublicTableQrResolver resolver;
    private final PublicQrMenuAccessPolicy access;
    private final PublicMenuVisibilityPolicy visibility;
    private final ItemGroupRepository groups;
    private final ItemRepository items;
    private final PublicMenuResponseMapper mapper;

    public PublicQrMenuResponse context(String token) {
        var context = resolver.resolve(token);
        access.requireAccess(context.restaurantId());
        var visibleGroups = groups.findAll(PublicMenuSpecifications.groups(context.restaurantId()),
                Sort.by("displayOrder", "name", "id"));
        return mapper.context(context, visibleGroups);
    }

    public PageResponse<PublicMenuItemResponse> items(String token, PublicMenuSearch search) {
        var pageable = search.pageable();
        var context = resolver.resolve(token);
        var tenant = context.restaurantId();
        access.requireAccess(tenant);
        if (search.getGroupId() != null) groups.findByIdAndRestaurantId(search.getGroupId(), tenant)
                .filter(g -> visibility.visibleGroup(g, tenant))
                .orElseThrow(() -> new ResourceNotFoundException("PUBLIC_MENU_GROUP_NOT_FOUND", "Nhóm món không còn khả dụng."));
        var page = items.findAll(PublicMenuSpecifications.items(tenant, search, visibility.visibleStatuses()), pageable);
        return new PageResponse<>(mapper.items(tenant, page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
