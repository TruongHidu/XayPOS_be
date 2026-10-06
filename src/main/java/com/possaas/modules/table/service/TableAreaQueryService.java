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
public class TableAreaQueryService {
    private final TableAccessService access;
    private final TableAreaRepository areas;

    public PageResponse<TableAreaResponse> search(TableSearch.Area search) {
        var actor = access.require(false);
        return PageResponse.from(areas.findAll(TableSpecifications.areas(actor.restaurantId(), search), search.pageable())
                .map(TableAreaResponse::from));
    }

    public TableAreaResponse detail(UUID id) {
        return TableAreaResponse.from(access.area(access.require(false).restaurantId(), id, false));
    }
}
