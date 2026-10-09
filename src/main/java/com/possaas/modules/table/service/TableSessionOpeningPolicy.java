package com.possaas.modules.table.service;

import com.possaas.common.security.CurrentUser;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Opening is an independently authorized side effect, not implied by ORDER_CREATE. */
@Component
public class TableSessionOpeningPolicy {
    public void requireAllowed(CurrentUser actor,UUID tenant) {
        if(tenant==null || !tenant.equals(actor.restaurantId()) || !actor.hasPermission("TABLE_OPEN"))
            throw new AccessDeniedException("Opening a table session requires TABLE_OPEN in the current tenant");
    }
}
