package com.possaas.modules.restaurant.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * The owner's own switches for guest QR ordering. They only ever narrow what the package entitles:
 * an entry point is open to guests when it is entitled and switched on here.
 */
public record QrOrderingSettings(boolean tableEnabled, boolean takeawayEnabled) {
    public static final QrOrderingSettings ALL_ENABLED = new QrOrderingSettings(true, true);

    public boolean allows(QrOrderingEntry entry) {
        return switch (entry) {
            case TABLE -> tableEnabled;
            case TAKEAWAY -> takeawayEnabled;
        };
    }

    /** Entry points that were on in {@code previous} and are off now. */
    public Set<QrOrderingEntry> newlyDisabled(QrOrderingSettings previous) {
        var result = EnumSet.noneOf(QrOrderingEntry.class);
        for (var entry : QrOrderingEntry.values())
            if (previous.allows(entry) && !allows(entry)) result.add(entry);
        return result;
    }
}
