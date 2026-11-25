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
    static final String NOT_FOUND = "MENU_ITEM_NOT_FOUND";
    static final String NOT_SELLABLE = "ITEM_NOT_SELLABLE";
    static final String OUT_OF_STOCK = "ITEM_OUT_OF_STOCK";

    private final ItemRepository items;
    private final ItemGroupRepository groups;
    private final MenuVisibilityPolicy visibility;

    @Override public Map<UUID,SellableMenuItem> requireSellable(UUID tenant, Collection<UUID> requested) {
        var judged = judge(tenant, requested);
        var result = new HashMap<UUID,SellableMenuItem>();
        // Requested order decides which failure is reported, as before.
        for(UUID id:requested) {
            var sellability = judged.get(id);
            if(!sellability.isSellable()) throw failure(sellability.unavailableCode());
            result.put(id,sellability.item());
        }
        return Map.copyOf(result);
    }

    @Override public Map<UUID,Sellability> inspectSellable(UUID tenant, Collection<UUID> requested) {
        return judge(tenant, requested);
    }

    private Map<UUID,Sellability> judge(UUID tenant, Collection<UUID> requested) {
        if(requested.isEmpty()) return Map.of();
        var found = items.findAllByRestaurantIdAndIdIn(tenant,new HashSet<>(requested));
        var groupIds = found.stream().map(Item::getGroupId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID,ItemGroup> byGroup = groupIds.isEmpty()?Map.of():groups.findAllByRestaurantIdAndIdIn(tenant,groupIds).stream()
                .collect(Collectors.toMap(ItemGroup::getId,Function.identity()));
        var byId = found.stream().collect(Collectors.toMap(Item::getId,Function.identity()));
        var result = new HashMap<UUID,Sellability>();
        for(UUID id:requested) result.put(id,judge(id,byId.get(id),byGroup));
        return Map.copyOf(result);
    }

    private Sellability judge(UUID id, Item item, Map<UUID,ItemGroup> byGroup) {
        if(item==null || item.getDeletedAt()!=null) return Sellability.unavailable(NOT_FOUND);
        var group=item.getGroupId()==null?null:byGroup.get(item.getGroupId());
        if(item.getItemType()!=ItemType.MENU_ITEM || !item.isActive()
                || item.getGroupId()!=null && (group==null || !group.isActive() || group.getDeletedAt()!=null))
            return Sellability.unavailable(NOT_SELLABLE);
        if(item.getAvailabilityStatus()==AvailabilityStatus.OUT_OF_STOCK) return Sellability.unavailable(OUT_OF_STOCK);
        if(!visibility.sellable(item,group)) return Sellability.unavailable(NOT_SELLABLE);
        return Sellability.sellable(new SellableMenuItem(id,item.getName(),item.getBaseUnit(),item.getSalePrice()));
    }

    private static BusinessException failure(String code) {
        return switch (code) {
            case NOT_FOUND -> new ResourceNotFoundException(NOT_FOUND,"Menu item not found");
            case OUT_OF_STOCK -> new ConflictException(OUT_OF_STOCK,"Item is out of stock");
            default -> new ConflictException(NOT_SELLABLE,"Item is not sellable");
        };
    }
}
