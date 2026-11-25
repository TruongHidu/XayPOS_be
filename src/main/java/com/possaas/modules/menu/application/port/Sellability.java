package com.possaas.modules.menu.application.port;

/**
 * Whether one menu item can be sold right now: either the authoritative item, or the stable code that
 * {@link OrderMenuQuery#requireSellable} would fail with (MENU_ITEM_NOT_FOUND, ITEM_NOT_SELLABLE, ITEM_OUT_OF_STOCK).
 */
public record Sellability(SellableMenuItem item, String unavailableCode) {
    public static Sellability sellable(SellableMenuItem item) { return new Sellability(item, null); }
    public static Sellability unavailable(String code) { return new Sellability(null, code); }
    public boolean isSellable() { return item != null; }
}
