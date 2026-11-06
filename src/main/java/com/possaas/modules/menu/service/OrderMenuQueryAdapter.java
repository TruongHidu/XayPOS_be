package com.possaas.modules.menu.service;

import com.possaas.common.exception.*;
import com.possaas.modules.menu.application.port.*;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class OrderMenuQueryAdapter implements OrderMenuQuery {
    private final ItemRepository items;
    private final ItemGroupRepository groups;
    private final MenuVisibilityPolicy visibility;

    @Override public Map<UUID,SellableMenuItem> requireSellable(UUID tenant, Collection<UUID> requested) {
        if(requested.isEmpty()) return Map.of();
        var found = items.findAllByRestaurantIdAndIdIn(tenant,new HashSet<>(requested));
        var groupIds = found.stream().map(Item::getGroupId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID,ItemGroup> byGroup = groupIds.isEmpty()?Map.of():groups.findAllByRestaurantIdAndIdIn(tenant,groupIds).stream()
                .collect(Collectors.toMap(ItemGroup::getId,Function.identity()));
        var byId = found.stream().collect(Collectors.toMap(Item::getId,Function.identity()));
        var result = new HashMap<UUID,SellableMenuItem>();
        for(UUID id:requested) {
            var item=byId.get(id);
            if(item==null || item.getDeletedAt()!=null) throw new ResourceNotFoundException("MENU_ITEM_NOT_FOUND","Menu item not found");
            var group=item.getGroupId()==null?null:byGroup.get(item.getGroupId());
            if(item.getItemType()!=ItemType.MENU_ITEM || !item.isActive()
                    || item.getGroupId()!=null && (group==null || !group.isActive() || group.getDeletedAt()!=null))
                throw new ConflictException("ITEM_NOT_SELLABLE","Item is not sellable");
            if(item.getAvailabilityStatus()==AvailabilityStatus.OUT_OF_STOCK) throw new ConflictException("ITEM_OUT_OF_STOCK","Item is out of stock");
            if(!visibility.sellable(item,group)) throw new ConflictException("ITEM_NOT_SELLABLE","Item is not sellable");
            result.put(id,new SellableMenuItem(id,item.getName(),item.getBaseUnit(),item.getSalePrice()));
        }
        return Map.copyOf(result);
    }
}
