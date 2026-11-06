package com.possaas.modules.menu.application.port;

import com.possaas.common.security.CurrentUser;
import java.util.UUID;

/** Notify inventory after an actual tracking/unit/deletion change, inside the MENU transaction. */
public interface IngredientInventoryAlertLifecycle {
    void changed(CurrentUser actor,UUID ingredientId,String ipAddress);
}
