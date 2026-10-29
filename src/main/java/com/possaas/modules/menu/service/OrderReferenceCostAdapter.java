package com.possaas.modules.menu.service;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.menu.service.cost.IngredientCostProvider;
import com.possaas.modules.order.application.port.OrderReferenceCostQuery;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
public class OrderReferenceCostAdapter implements OrderReferenceCostQuery {
    private final ItemRepository items;
    private final IngredientCostProvider ingredients;
    private final RestaurantRepository restaurants;
    @Override public Prices findManualCosts(UUID tenant, Collection<UUID> ids) {
        if(ids.isEmpty()) return new Prices(null,Map.of());
        var prices=items.findAllByRestaurantIdAndIdIn(tenant,ids).stream()
            .filter(item->item.getItemType()==ItemType.MENU_ITEM && item.getDeletedAt()==null)
            .collect(Collectors.toMap(item->item.getId(),item->new Price(item.getId(),item.getBaseUnit(),
                item.getCostPrice(),item.isCostConfigured())));
        return new Prices(currency(tenant),prices);
    }
    @Override public Prices findIngredientCosts(UUID tenant, Collection<UUID> ids) {
        if(ids.isEmpty()) return new Prices(null,Map.of());
        var prices=ingredients.findCosts(tenant,ids).values().stream().collect(Collectors.toMap(
            item->item.ingredientId(),item->new Price(item.ingredientId(),item.baseUnit(),item.unitCost(),item.configured())));
        return new Prices(currency(tenant),prices);
    }
    private String currency(UUID tenant) {
        return restaurants.findByIdAndDeletedAtIsNull(tenant).orElseThrow(()->
            new ResourceNotFoundException("RESTAURANT_NOT_FOUND","Restaurant not found")).getCurrencyCode();
    }
}
