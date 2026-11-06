package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.inventory.entity.InventoryBalance;
import org.springframework.stereotype.Component;

@Component
public class InventoryBalancePolicy {
    public void requireExistingVersion(InventoryBalance balance, Long expectedVersion) {
        if (expectedVersion == null || balance.getVersion() != expectedVersion)
            conflict();
    }

    public void requireLogicalVersion(Long expectedVersion) {
        if (expectedVersion != null)
            conflict();
    }

    private static void conflict() {
        throw new ConflictException("CONCURRENT_INVENTORY_UPDATE",
                "Inventory balance changed; reload before retrying");
    }
}
