package com.possaas.modules.table.service;

import com.possaas.common.exception.*;
import com.possaas.modules.table.application.port.OrderSeatingQuery;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class OrderSeatingQueryAdapter implements OrderSeatingQuery {
    private final TableSessionRepository sessions;
    private final RestaurantTableRepository tables;
    private final TableAreaRepository areas;
    private final TableAccessService access;
    private final TableSessionOpeningPolicy opening;

    @Override public Seating requireOpen(UUID tenant,UUID id) {
        var session=sessions.findByIdAndRestaurantId(id,tenant)
                .orElseThrow(()->new ResourceNotFoundException("TABLE_SESSION_NOT_FOUND","Session not found"));
        if(session.getStatus()!=TableSessionStatus.OPEN) throw new ConflictException("TABLE_SESSION_NOT_OPEN","Session must be OPEN");
        requireEligibleTable(tenant,session.getTableId());
        return new Seating(id,session.getGuestCount());
    }

    @Override public TableTarget resolveTable(UUID tenant,UUID tableId) {
        var actor=access.require(false);
        if(!tenant.equals(actor.restaurantId()))
            throw new org.springframework.security.access.AccessDeniedException("Current tenant is required");
        requireEligibleTable(tenant,tableId);
        var found=sessions.findAllByRestaurantIdAndTableIdAndStatus(tenant,tableId,TableSessionStatus.OPEN);
        if(found.size()>1) throw new ConflictException("INCONSISTENT_TABLE_SESSION_STATE","Table has inconsistent open session data");
        if(!found.isEmpty()) {
            var session=found.getFirst();
            return new Existing(new Seating(session.getId(),session.getGuestCount()));
        }
        opening.requireAllowed(actor,tenant);
        return new Unoccupied(tableId);
    }

    private void requireEligibleTable(UUID tenant,UUID tableId) {
        var table=tables.findByIdAndRestaurantId(tableId,tenant).filter(t->t.getDeletedAt()==null)
                .orElseThrow(()->new ResourceNotFoundException("TABLE_NOT_FOUND","Table not found"));
        if(table.getStatus()!=TableStatus.AVAILABLE) throw new ConflictException("TABLE_INACTIVE","Table is inactive");
        if(table.getAreaId()!=null) {
            var area=areas.findByIdAndRestaurantId(table.getAreaId(),tenant).filter(a->a.getDeletedAt()==null)
                    .orElseThrow(()->new ResourceNotFoundException("TABLE_AREA_NOT_FOUND","Area not found"));
            if(!area.isActive()) throw new ConflictException("TABLE_AREA_INACTIVE","Area is inactive");
        }
    }
}
