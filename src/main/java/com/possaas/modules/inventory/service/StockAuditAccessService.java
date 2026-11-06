package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class StockAuditAccessService {
    private final InventoryAccessService inventory;
    private final FeatureAccessChecker features;
    public CurrentUser require(boolean mutation){
        var actor=inventory.require(mutation);
        features.requireFeature(actor.restaurantId(),"STOCK_AUDIT");
        return actor;
    }
}
