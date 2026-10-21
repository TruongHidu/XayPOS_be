package com.possaas.modules.order.application.port;

import com.possaas.common.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Synchronous internal inventory side effects; the caller holds the restaurant mutation lock. */
public interface OrderInventoryAllocationCommand {
    void reserve(CurrentUser actor, UUID orderId, List<AcceptedItem> items, Instant now, String ip);
    void consume(CurrentUser actor, UUID orderId, Collection<UUID> itemIds, Instant now, String ip);
    void release(CurrentUser actor, UUID orderId, Collection<UUID> itemIds, Instant now, String ip);

    /** Checks durable decisions, reservation details and ledger without changing historical data. */
    void validate(UUID tenant, UUID orderId, List<LineState> lines);

    record AcceptedItem(UUID orderItemId, UUID recipeSnapshotId, String snapshotStatus,
            BigDecimal orderQuantity, String orderUnit, List<Usage> usages) {
        public AcceptedItem { usages = List.copyOf(usages); }
    }

    /** Only tracked usages are sent; quantity is the immutable Phase 4 required quantity. */
    record Usage(UUID recipeUsageId, UUID ingredientId, String sku, String name, String unit,
            BigDecimal quantity) {}

    record LineState(UUID orderItemId, String status, boolean submitted, BigDecimal quantity, String unit) {}
}
