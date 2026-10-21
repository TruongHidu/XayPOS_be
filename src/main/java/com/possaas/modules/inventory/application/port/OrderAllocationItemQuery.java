package com.possaas.modules.inventory.application.port;

import java.math.BigDecimal;
import java.util.UUID;

/** Internal parent/child ownership lookup supplied by ORDER, without management feature checks. */
public interface OrderAllocationItemQuery {
    Item find(UUID tenant, UUID orderId, UUID orderItemId);

    record Item(UUID orderId, UUID orderItemId, String status, boolean submitted,
            BigDecimal quantity, String unit) {}
}
