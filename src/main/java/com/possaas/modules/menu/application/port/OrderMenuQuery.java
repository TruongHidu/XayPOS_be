package com.possaas.modules.menu.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface OrderMenuQuery {
    Map<UUID,SellableMenuItem> requireSellable(UUID restaurantId, Collection<UUID> itemIds);
}
