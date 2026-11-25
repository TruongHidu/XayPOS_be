package com.possaas.modules.qrorder.entity;

import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.restaurant.entity.QrOrderingEntry;
import java.util.List;

/** Server-owned origin, never accepted in a guest command. */
public enum QrEntryType {
    TABLE, RESTAURANT;
    private static final List<String> TABLE_FEATURES = List.of("QR_MENU_VIEW", "ORDER_MANAGEMENT", "QR_TABLE_ORDER", "TABLE_MANAGEMENT");
    private static final List<String> RESTAURANT_FEATURES = List.of("QR_MENU_VIEW", "ORDER_MANAGEMENT", "QR_STATIC_ORDER");

    public ServiceType serviceType() { return this == TABLE ? ServiceType.DINE_IN : ServiceType.TAKEAWAY; }
    public SourceChannel sourceChannel() { return this == TABLE ? SourceChannel.QR_TABLE : SourceChannel.QR_STATIC; }
    /** The owner's switch that governs this entry point. */
    public QrOrderingEntry orderingEntry() { return this == TABLE ? QrOrderingEntry.TABLE : QrOrderingEntry.TAKEAWAY; }
    public static QrEntryType of(QrOrderingEntry entry) { return entry == QrOrderingEntry.TABLE ? TABLE : RESTAURANT; }
    /** Package features that must all be entitled for guests to order through this entry point. */
    public List<String> requiredFeatures() { return this == TABLE ? TABLE_FEATURES : RESTAURANT_FEATURES; }
}
