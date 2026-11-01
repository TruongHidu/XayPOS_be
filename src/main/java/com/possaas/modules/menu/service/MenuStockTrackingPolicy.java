package com.possaas.modules.menu.service;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.menu.application.port.InventoryStockUsageQuery;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class MenuStockTrackingPolicy {
    private final StockTrackingModeResolver modes;
    private final InventoryStockUsageQuery usage;
    private final FeatureAccessChecker features;
    public void configure(UUID tenant, boolean tracked, boolean recipe, boolean enabling) {
        modes.menuMode(tracked,recipe);
        if(enabling) features.requireFeature(tenant,"INVENTORY_MANAGEMENT");
    }
    public void requireIdle(Item item, String code) {
        UUID tenant=item.getRestaurantId(), id=item.getId();
        if(usage.hasNonZeroStock(tenant,id) || usage.hasReservations(tenant,id) || usage.hasDraftDocuments(tenant,id))
            throw new ConflictException(code,"Item still has stock, reservations or draft stock documents");
    }
}
