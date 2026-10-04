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
public class MenuGroupQueryService {
    private final MenuAccessService access;
    private final ItemGroupRepository groups;

    public PageResponse<MenuGroupResponse> search(MenuSearch search) {
        var actor = access.require(false);
        return PageResponse
                .from(groups.findAll(MenuSpecifications.groups(actor.restaurantId(), search), search.pageable(false))
                        .map(MenuGroupResponse::from));
    }

    public MenuGroupResponse detail(UUID id) {
        return MenuGroupResponse.from(access.group(access.require(false).restaurantId(), id, false));
    }
}
