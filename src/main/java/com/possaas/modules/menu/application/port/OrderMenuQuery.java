package com.possaas.modules.menu.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface OrderMenuQuery {
    /** All requested items must be sellable; the first one that is not fails the whole call. */
    Map<UUID,SellableMenuItem> requireSellable(UUID restaurantId, Collection<UUID> itemIds);

    /**
     * The same rules as {@link #requireSellable}, judged item by item and never thrown, so a caller can
     * show which items are unavailable without its transaction being marked rollback-only.
     */
    Map<UUID,Sellability> inspectSellable(UUID restaurantId, Collection<UUID> itemIds);
}
