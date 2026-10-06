package com.possaas.modules.table.service;

import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.table.dto.*;
import com.possaas.modules.table.repository.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantTableQueryService {
    private final TableAccessService access;
    private final RestaurantTableRepository tables;
    private final TableResponseMapper mapper;

    public PageResponse<RestaurantTableResponse> search(TableSearch.Tables search) {
        var actor = access.require(false);
        if (search.getAreaId() != null) access.area(actor.restaurantId(), search.getAreaId(), false);
        var page = tables.findAll(TableSpecifications.tables(actor.restaurantId(), search), search.pageable());
        return new PageResponse<>(mapper.tables(actor.restaurantId(), page.getContent()), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public RestaurantTableResponse detail(UUID id) {
        return mapper.table(access.table(access.require(false).restaurantId(), id, false));
    }

    public TableQrResponse qr(UUID id) {
        return TableQrResponse.from(access.table(access.require(false).restaurantId(), id, false));
    }
}
