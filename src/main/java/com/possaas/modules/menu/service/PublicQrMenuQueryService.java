package com.possaas.modules.menu.service;

import com.possaas.modules.menu.application.port.PublicOrderingAvailabilityQuery;
import com.possaas.modules.menu.dto.*;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.table.service.PublicTableQrResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicQrMenuQueryService {
    private final PublicTableQrResolver resolver;
    private final PublicQrMenuAccessPolicy access;
    private final PublicMenuReadService menu;
    private final PublicMenuResponseMapper mapper;
    private final PublicOrderingAvailabilityQuery ordering;

    public PublicQrMenuResponse context(String token) {
        var context = resolver.resolve(token);
        access.requireAccess(context.restaurantId());
        return mapper.context(context, menu.groups(context.restaurantId()), ordering.tableOrderingEnabled(context.restaurantId()));
    }

    public PageResponse<PublicMenuItemResponse> items(String token, PublicMenuSearch search) {
        search.pageable();
        var context = resolver.resolve(token);
        access.requireAccess(context.restaurantId());
        return menu.items(context.restaurantId(), search);
    }
}
