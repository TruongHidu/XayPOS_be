package com.possaas.modules.menu.application.port;

import java.util.UUID;

/**
 * MENU-owned read port: whether guests viewing a public menu can also order from it, so the page can hide
 * its cart. Only a hint for display; ordering itself is enforced where the order is submitted. Never throws
 * for an unavailable entry point, so it is safe inside the menu's read-only transaction.
 */
public interface PublicOrderingAvailabilityQuery {
    boolean tableOrderingEnabled(UUID restaurantId);

    boolean takeawayOrderingEnabled(UUID restaurantId);
}
