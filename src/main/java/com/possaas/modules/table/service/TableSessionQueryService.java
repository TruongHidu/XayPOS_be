package com.possaas.modules.table.service;

import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.table.dto.*;
import com.possaas.modules.table.entity.TableSessionStatus;
import com.possaas.modules.table.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TableSessionQueryService {
    private final TableAccessService access;
    private final TableSessionRepository sessions;
    private final TableResponseMapper mapper;

    public PageResponse<TableSessionResponse> search(TableSearch.Sessions search) {
        var actor = access.require(false);
        if (search.getTableId() != null) access.table(actor.restaurantId(), search.getTableId(), true);
        var page = sessions.findAll(TableSpecifications.sessions(actor.restaurantId(), search), search.pageable());
        return new PageResponse<>(mapper.sessions(actor.restaurantId(), page.getContent()), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public TableSessionResponse detail(UUID id) {
        return mapper.session(access.session(access.require(false).restaurantId(), id));
    }

    public Optional<TableSessionResponse> current(UUID tableId) {
        var actor = access.require(false);
        access.table(actor.restaurantId(), tableId, false);
        return sessions.findByRestaurantIdAndTableIdAndStatus(actor.restaurantId(), tableId, TableSessionStatus.OPEN)
                .map(mapper::session);
    }
}
