package com.possaas.modules.menu.service;

import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.dto.MenuItemResponse;
import com.possaas.modules.menu.repository.ItemGroupRepository;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MenuResponseMapper {
    private final ItemGroupRepository groups;
    private final MenuVisibilityPolicy visibility;

    public List<MenuItemResponse> items(UUID tenant, List<Item> items) {
        var ids = items.stream().map(Item::getGroupId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, ItemGroup> map = ids.isEmpty() ? Map.of()
                : groups.findAllByRestaurantIdAndIdIn(tenant, ids).stream()
                        .collect(Collectors.toMap(ItemGroup::getId, g -> g));
        return items.stream().map(i -> {
            var g = i.getGroupId() == null ? null : map.get(i.getGroupId());
            return MenuItemResponse.from(i, g, visibility.sellable(i, g));
        }).toList();
    }

    public MenuItemResponse item(Item item) {
        return items(item.getRestaurantId(), List.of(item)).getFirst();
    }
}
