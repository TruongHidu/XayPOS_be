package com.possaas.modules.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import com.possaas.modules.restaurant.entity.QrOrderingEntry;
import com.possaas.modules.restaurant.entity.QrOrderingSettings;
import com.possaas.modules.restaurant.entity.Restaurant;
import org.junit.jupiter.api.Test;

class QrOrderingSettingsTest {
    @Test void eachSwitchGovernsOnlyItsOwnEntryPoint() {
        var tableOff = new QrOrderingSettings(false, true);
        assertThat(tableOff.allows(QrOrderingEntry.TABLE)).isFalse();
        assertThat(tableOff.allows(QrOrderingEntry.TAKEAWAY)).isTrue();
    }

    @Test void onlyOnToOffCountsAsNewlyDisabled() {
        var allOn = QrOrderingSettings.ALL_ENABLED;
        var allOff = new QrOrderingSettings(false, false);
        assertThat(allOff.newlyDisabled(allOn)).containsExactlyInAnyOrder(QrOrderingEntry.TABLE, QrOrderingEntry.TAKEAWAY);
        assertThat(new QrOrderingSettings(false, true).newlyDisabled(allOn)).containsExactly(QrOrderingEntry.TABLE);
        assertThat(allOff.newlyDisabled(allOff)).isEmpty();
        assertThat(allOn.newlyDisabled(allOff)).isEmpty();
    }

    @Test void newRestaurantsAcceptQrOrdersUntilTheOwnerSwitchesThemOff() {
        var restaurant = new Restaurant();
        assertThat(restaurant.qrOrdering()).isEqualTo(QrOrderingSettings.ALL_ENABLED);
        restaurant.changeQrOrdering(new QrOrderingSettings(true, false));
        assertThat(restaurant.qrOrdering()).isEqualTo(new QrOrderingSettings(true, false));
    }
}
