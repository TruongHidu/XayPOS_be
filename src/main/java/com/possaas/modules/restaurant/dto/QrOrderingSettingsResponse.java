package com.possaas.modules.restaurant.dto;

import com.possaas.modules.restaurant.entity.QrOrderingSettings;

/** The owner's switches only; whether the package entitles each entry point comes from the subscription features. */
public record QrOrderingSettingsResponse(boolean tableEnabled, boolean takeawayEnabled) {
    public static QrOrderingSettingsResponse from(QrOrderingSettings settings) {
        return new QrOrderingSettingsResponse(settings.tableEnabled(), settings.takeawayEnabled());
    }
}
