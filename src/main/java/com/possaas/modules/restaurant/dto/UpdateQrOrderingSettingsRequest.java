package com.possaas.modules.restaurant.dto;

import com.possaas.modules.restaurant.entity.QrOrderingSettings;
import jakarta.validation.constraints.NotNull;

/** Both switches, always: a full replacement keeps a stale screen from silently flipping the other one. */
public record UpdateQrOrderingSettingsRequest(@NotNull Boolean tableEnabled, @NotNull Boolean takeawayEnabled) {
    public QrOrderingSettings toSettings() {
        return new QrOrderingSettings(tableEnabled, takeawayEnabled);
    }
}
