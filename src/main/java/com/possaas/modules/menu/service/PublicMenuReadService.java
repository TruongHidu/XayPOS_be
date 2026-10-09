package com.possaas.modules.menu.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.menu.dto.*;
import com.possaas.modules.menu.entity.ItemGroup;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicMenuReadService {
    private final PublicMenuVisibilityPolicy visibility;
    private final ItemGroupRepository groups;
    private final ItemRepository items;
    private final PublicMenuResponseMapper mapper;

    public List<ItemGroup> groups(UUID tenant) {
        return groups.findAll(PublicMenuSpecifications.groups(tenant), Sort.by("displayOrder", "name", "id"));
    }

    public PageResponse<PublicMenuItemResponse> items(UUID tenant, PublicMenuSearch search) {
        var pageable = search.pageable();
        if (search.getGroupId() != null) groups.findByIdAndRestaurantId(search.getGroupId(), tenant)
                .filter(g -> visibility.visibleGroup(g, tenant))
                .orElseThrow(() -> new ResourceNotFoundException("PUBLIC_MENU_GROUP_NOT_FOUND", "Nhóm món không còn khả dụng."));
        var page = items.findAll(PublicMenuSpecifications.items(tenant, search, visibility.visibleStatuses()), pageable);
        return new PageResponse<>(mapper.items(tenant, page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
