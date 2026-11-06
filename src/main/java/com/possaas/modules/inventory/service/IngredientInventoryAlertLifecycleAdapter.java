package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.menu.application.port.IngredientInventoryAlertLifecycle;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class IngredientInventoryAlertLifecycleAdapter implements IngredientInventoryAlertLifecycle {
    private final LowStockAlertReconciliationService alerts;
    public void changed(CurrentUser actor,UUID ingredientId,String ipAddress) {
        alerts.ingredientChanged(actor,ingredientId,ipAddress);
    }
}
