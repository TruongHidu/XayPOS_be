package com.possaas.infrastructure.realtime;

import java.util.Arrays;
import java.util.Set;
import com.possaas.modules.subscription.domain.CurrentEntitlement;

/** Read policies, independent of CONNECT and of mutation permissions. */
public enum RealtimeChannel {
    INVENTORY_ALERTS("inventory-alerts", "INVENTORY_VIEW", Set.of("INVENTORY_MANAGEMENT", "LOW_STOCK_ALERT")),
    QR_REQUESTS("qr-order-requests", "ORDER_VIEW", Set.of("ORDER_MANAGEMENT")),
    ORDERS("orders", "ORDER_VIEW", Set.of("ORDER_MANAGEMENT")),
    KITCHEN("kitchen-orders", "KITCHEN_VIEW", Set.of("KITCHEN_DISPLAY"));

    // Preserve the existing inventory socket policy; do not impose it on other channels.
    private static final Set<String> INVENTORY_ROLES = Set.of("OWNER", "MANAGER", "WAITER", "KITCHEN");
    private final String name;
    private final String permission;
    private final Set<String> features;
    RealtimeChannel(String name, String permission, Set<String> features) {
        this.name = name; this.permission = permission; this.features = features;
    }
    public String destination() { return "/user" + queue(); }
    public String queue() { return "/queue/" + name; }
    public static RealtimeChannel fromDestination(String destination) {
        return Arrays.stream(values()).filter(c -> c.destination().equals(destination)).findFirst()
                .orElseThrow(() -> new RealtimeRejected("FORBIDDEN"));
    }
    public void require(String role, Set<String> permissions, CurrentEntitlement entitlement) {
        if (!permissions.contains(permission) || this == INVENTORY_ALERTS && !INVENTORY_ROLES.contains(role))
            throw new RealtimeRejected("FORBIDDEN");
        if (!features.stream().allMatch(entitlement::hasFeature)) throw new RealtimeRejected("FEATURE_NOT_ENTITLED");
    }
}
