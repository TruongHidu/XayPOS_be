package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.application.port.RestaurantQrOrderingLifecycle;
import com.possaas.modules.restaurant.dto.QrOrderingSettingsResponse;
import com.possaas.modules.restaurant.dto.UpdateQrOrderingSettingsRequest;
import com.possaas.modules.restaurant.entity.QrOrderingSettings;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Switching guest QR ordering on or off. Runs under the restaurant lock that guest access and submission
 * also take, so no guest can slip a request in while an entry point is being closed. Closing an entry point
 * withdraws its open guest sessions and pending requests in the same transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantQrOrderingCommandService {
    static final String UPDATED = "RESTAURANT_QR_ORDERING_UPDATED";

    private final CurrentTenantProvider tenant;
    private final CurrentUserProvider users;
    private final RestaurantRepository restaurants;
    private final AuditService audit;
    private final RestaurantQrOrderingLifecycle lifecycle;
    private final Clock clock;

    public QrOrderingSettingsResponse update(UpdateQrOrderingSettingsRequest request, String ip) {
        Restaurant restaurant = restaurants.findByIdForUpdate(tenant.getRequiredRestaurantId())
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        QrOrderingSettings previous = restaurant.qrOrdering();
        QrOrderingSettings next = request.toSettings();
        if (!previous.equals(next)) {
            var actor = users.getRequired().userId();
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            restaurant.changeQrOrdering(next);
            restaurants.saveAndFlush(restaurant);
            audit.record(restaurant.getId(), actor, UPDATED, "restaurants", restaurant.getId(),
                auditView(previous), auditView(next), ip);
            for (var entry : next.newlyDisabled(previous))
                lifecycle.qrOrderingDisabled(restaurant.getId(), entry, actor, now, ip);
        }
        return QrOrderingSettingsResponse.from(restaurant.qrOrdering());
    }

    private static Map<String, Object> auditView(QrOrderingSettings settings) {
        return Map.of("tableEnabled", settings.tableEnabled(), "takeawayEnabled", settings.takeawayEnabled());
    }
}
