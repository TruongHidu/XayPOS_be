package com.possaas.modules.qrorder.event;

import java.util.List;
import java.util.UUID;

/** Bounded cancellation batch; each request keeps its own immutable version and event ID. */
public record QrOrderRequestsChanged(UUID restaurantId, List<QrOrderRequestChanged> requests) {
    public QrOrderRequestsChanged {
        requests = List.copyOf(requests);
        if (requests.isEmpty() || requests.size() > 100
                || requests.stream().anyMatch(r -> !r.restaurantId().equals(restaurantId)))
            throw new IllegalArgumentException("Invalid QR event batch");
    }
}
