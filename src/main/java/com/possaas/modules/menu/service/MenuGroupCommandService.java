package com.possaas.modules.menu.service;

import com.possaas.common.exception.*;
import com.possaas.modules.menu.dto.*;
import com.possaas.modules.menu.entity.ItemGroup;
import com.possaas.modules.menu.repository.*;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MenuGroupCommandService {
    private final MenuAccessService access;
    private final ItemGroupRepository groups;
    private final ItemRepository items;
    private final MenuItemValidationPolicy validation;
    private final MenuItemLifecyclePolicy lifecycle;
    private final MenuAudit audit;
    private final Clock clock;

    public MenuGroupResponse create(MenuRequests.CreateGroup r, String ip) {
        var actor = access.require(true);
        var g = new ItemGroup();
        g.initialize(actor.restaurantId(), clock.instant());
        g.setName(validation.required(r.name(), 100));
        g.setDisplayOrder(r.displayOrder() == null ? 0 : r.displayOrder());
        g.setActive(r.active() == null || r.active());
        groups.saveAndFlush(g);
        audit.record(actor, "MENU_GROUP_CREATED", g, null, audit.snapshot(g), ip);
        return MenuGroupResponse.from(g);
    }

    public MenuGroupResponse update(UUID id, MenuRequests.UpdateGroup r, String ip) {
        var actor = access.require(true);
        var g = access.group(actor.restaurantId(), id, false);
        if (r.name() == null && r.displayOrder() == null)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_UPDATE_REQUEST", "No fields provided");
        lifecycle.requireVersion(g, r.expectedVersion());
        var before = audit.snapshot(g);
        if (r.name() != null)
            g.setName(validation.required(r.name(), 100));
        if (r.displayOrder() != null)
            g.setDisplayOrder(r.displayOrder());
        var after = audit.snapshot(g);
        if (!before.equals(after)) {
            g.setUpdatedAt(clock.instant());
            groups.saveAndFlush(g);
            audit.record(actor, "MENU_GROUP_UPDATED", g, before, after, ip);
        }
        return MenuGroupResponse.from(g);
    }

    public MenuGroupResponse status(UUID id, MenuRequests.Status r, String ip) {
        var actor = access.require(true);
        var g = access.group(actor.restaurantId(), id, false);
        if (g.isActive() == r.active())
            return MenuGroupResponse.from(g);
        lifecycle.requireVersion(g, r.expectedVersion());
        var before = audit.snapshot(g);
        g.setActive(r.active());
        g.setUpdatedAt(clock.instant());
        groups.saveAndFlush(g);
        audit.record(actor, r.active() ? "MENU_GROUP_ENABLED" : "MENU_GROUP_DISABLED", g, before, audit.snapshot(g),
                ip);
        return MenuGroupResponse.from(g);
    }

    public void delete(UUID id, long version, String ip) {
        var actor = access.require(true);
        var g = access.group(actor.restaurantId(), id, true);
        if (g.getDeletedAt() != null)
            return;
        lifecycle.requireVersion(g, version);
        if (items.existsByRestaurantIdAndGroupIdAndDeletedAtIsNull(actor.restaurantId(), id))
            throw new ConflictException("MENU_GROUP_NOT_EMPTY", "Move or delete the group's items first");
        var before = audit.snapshot(g);
        var now = clock.instant();
        g.setDeletedAt(now);
        g.setUpdatedAt(now);
        groups.saveAndFlush(g);
        audit.record(actor, "MENU_GROUP_DELETED", g, before, audit.snapshot(g), ip);
    }
}
