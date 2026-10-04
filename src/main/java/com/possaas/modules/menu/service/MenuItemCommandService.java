package com.possaas.modules.menu.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.menu.dto.*;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.menu.service.strategy.ItemCreationContext;
import com.possaas.common.security.CurrentUser;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MenuItemCommandService {
    private final MenuAccessService access;
    private final ItemRepository items;
    private final MenuItemFactory factory;
    private final MenuItemValidationPolicy validation;
    private final MenuItemLifecyclePolicy lifecycle;
    private final MenuResponseMapper mapper;
    private final MenuAudit audit;
    private final Clock clock;

    public MenuItemResponse create(MenuRequests.CreateItem r, String ip) {
        var actor = access.require(true);
        access.assignableGroup(actor.restaurantId(), r.groupId());
        var context = new ItemCreationContext(actor.restaurantId(), r.groupId(), validation.sku(r.sku()),
                validation.required(r.name(), 150), validation.required(r.baseUnit(), 30),
                validation.optional(r.description()), validation.image(r.imageUrl()), validation.price(r.salePrice()),
                r.active() == null || r.active(), clock.instant());
        var item = factory.create(ItemType.MENU_ITEM, context);
        items.saveAndFlush(item);
        audit.record(actor, "MENU_ITEM_CREATED", item, null, audit.snapshot(item), ip);
        return mapper.item(item);
    }

    public MenuItemResponse update(UUID id, MenuRequests.UpdateItem r, String ip) {
        var actor = access.require(true);
        var item = access.item(actor.restaurantId(), id, false);
        if (r.name() == null && r.sku() == null && r.baseUnit() == null && r.description() == null
                && r.imageUrl() == null && r.salePrice() == null)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_UPDATE_REQUEST", "No fields provided");
        lifecycle.requireVersion(item, r.expectedVersion());
        var before = audit.snapshot(item);
        if (r.name() != null)
            item.setName(validation.required(r.name(), 150));
        if (r.baseUnit() != null)
            item.setBaseUnit(validation.required(r.baseUnit(), 30));
        if (r.sku() != null)
            item.setSku(validation.sku(r.sku()));
        if (r.description() != null)
            item.setDescription(validation.optional(r.description()));
        if (r.imageUrl() != null)
            item.setImageUrl(validation.image(r.imageUrl()));
        if (r.salePrice() != null)
            item.setSalePrice(validation.price(r.salePrice()));
        if (!before.equals(audit.snapshot(item)))
            persist(actor, item, "MENU_ITEM_UPDATED", before, ip);
        return mapper.item(item);
    }

    public MenuItemResponse status(UUID id, MenuRequests.Status r, String ip) {
        var actor = access.require(true);
        var item = access.item(actor.restaurantId(), id, false);
        if (item.isActive() == r.active())
            return mapper.item(item);
        lifecycle.requireVersion(item, r.expectedVersion());
        var before = audit.snapshot(item);
        lifecycle.changeActive(item, r.active());
        persist(actor, item, r.active() ? "MENU_ITEM_ENABLED" : "MENU_ITEM_DISABLED", before, ip);
        return mapper.item(item);
    }

    public MenuItemResponse availability(UUID id, MenuRequests.Availability r, String ip) {
        var actor = access.require(true);
        var item = access.item(actor.restaurantId(), id, false);
        lifecycle.requireAvailabilityChange(item);
        if (item.getAvailabilityStatus() == r.availabilityStatus())
            return mapper.item(item);
        lifecycle.requireVersion(item, r.expectedVersion());
        var before = audit.snapshot(item);
        lifecycle.changeAvailability(item, r.availabilityStatus());
        persist(actor, item, "MENU_ITEM_AVAILABILITY_CHANGED", before, ip);
        return mapper.item(item);
    }

    public MenuItemResponse group(UUID id, MenuRequests.GroupAssignment r, String ip) {
        var actor = access.require(true);
        var item = access.item(actor.restaurantId(), id, false);
        if (Objects.equals(item.getGroupId(), r.groupId()))
            return mapper.item(item);
        lifecycle.requireVersion(item, r.expectedVersion());
        access.assignableGroup(actor.restaurantId(), r.groupId());
        var before = audit.snapshot(item);
        item.setGroupId(r.groupId());
        persist(actor, item, "MENU_ITEM_GROUP_CHANGED", before, ip);
        return mapper.item(item);
    }

    public void delete(UUID id, long version, String ip) {
        var actor = access.require(true);
        var item = access.item(actor.restaurantId(), id, true);
        if (item.getDeletedAt() != null)
            return;
        lifecycle.requireVersion(item, version);
        var before = audit.snapshot(item);
        lifecycle.softDelete(item, clock.instant());
        persist(actor, item, "MENU_ITEM_DELETED", before, ip);
    }

    private void persist(CurrentUser actor, Item item, String action, Map<String, Object> before, String ip) {
        item.setUpdatedAt(clock.instant());
        items.saveAndFlush(item);
        audit.record(actor, action, item, before, audit.snapshot(item), ip);
    }
}
