package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.application.port.RestaurantNoteStore;
import com.possaas.modules.restaurant.dto.AdminRestaurantNoteResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal support notes written by super admins; never exposed to the tenant. */
@Service
@RequiredArgsConstructor
public class AdminRestaurantNoteService {
    private static final int LIST_LIMIT = 100;

    private final AdminRestaurantLookup restaurants;
    private final RestaurantNoteStore notes;
    private final AuditService audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<AdminRestaurantNoteResponse> list(UUID restaurantId) {
        restaurants.requireExisting(restaurantId);
        return notes.findActive(restaurantId, LIST_LIMIT);
    }

    @Transactional
    public AdminRestaurantNoteResponse add(UUID restaurantId, String content, UUID actorId, String ip) {
        restaurants.requireExisting(restaurantId);
        UUID id = UUID.randomUUID();
        String text = content.trim();
        notes.insert(id, restaurantId, actorId, text, now());
        audit.record(restaurantId, actorId, "ADMIN_RESTAURANT_NOTE_ADDED", "restaurant_admin_notes", id, null,
            Map.of("length", text.length()), ip);
        return notes.findActive(restaurantId, id).orElseThrow();
    }

    @Transactional
    public void delete(UUID restaurantId, UUID noteId, UUID actorId, String ip) {
        if (!notes.softDelete(restaurantId, noteId, actorId, now()))
            throw new ResourceNotFoundException("NOTE_NOT_FOUND", "Note not found");
        audit.record(restaurantId, actorId, "ADMIN_RESTAURANT_NOTE_DELETED", "restaurant_admin_notes", noteId, null, null, ip);
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
