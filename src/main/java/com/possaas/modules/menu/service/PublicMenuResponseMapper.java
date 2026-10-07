package com.possaas.modules.menu.service;

import com.possaas.modules.menu.dto.PublicMenuItemResponse;
import com.possaas.modules.menu.dto.PublicQrMenuResponse;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemGroup;
import com.possaas.modules.menu.repository.ItemGroupRepository;
import com.possaas.modules.table.dto.PublicQrTableContext;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PublicMenuResponseMapper {
    private final ItemGroupRepository groups;

    public PublicQrMenuResponse context(PublicQrTableContext context, List<ItemGroup> visibleGroups) {
        return new PublicQrMenuResponse(
                new PublicQrMenuResponse.Restaurant(context.restaurantName(), context.currencyCode()),
                new PublicQrMenuResponse.Table(context.tableCode(), context.tableName()),
                visibleGroups.stream().map(g -> new PublicQrMenuResponse.Group(g.getId(), g.getName(), g.getDisplayOrder())).toList());
    }

    public List<PublicMenuItemResponse> items(UUID tenant, List<Item> items) {
        var ids = items.stream().map(Item::getGroupId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, ItemGroup> byId = ids.isEmpty() ? Map.of() : groups.findAllByRestaurantIdAndIdIn(tenant, ids)
                .stream().collect(Collectors.toMap(ItemGroup::getId, g -> g));
        return items.stream().map(i -> {
            var g = i.getGroupId() == null ? null : byId.get(i.getGroupId());
            return new PublicMenuItemResponse(i.getId(), g == null ? null : new PublicMenuItemResponse.Group(g.getId(), g.getName()),
                    i.getName(), i.getDescription(), i.getImageUrl(), i.getBaseUnit(), i.getSalePrice(), i.getAvailabilityStatus());
        }).toList();
    }
}
