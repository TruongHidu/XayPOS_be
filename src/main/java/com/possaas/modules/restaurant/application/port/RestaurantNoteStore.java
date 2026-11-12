package com.possaas.modules.restaurant.application.port;

import com.possaas.modules.restaurant.dto.AdminRestaurantNoteResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for internal super-admin notes about a restaurant. */
public interface RestaurantNoteStore {
    List<AdminRestaurantNoteResponse> findActive(UUID restaurantId, int limit);

    Optional<AdminRestaurantNoteResponse> findActive(UUID restaurantId, UUID noteId);

    void insert(UUID noteId, UUID restaurantId, UUID authorId, String content, Instant createdAt);

    /** @return false when the note does not exist, belongs to another restaurant or is already deleted */
    boolean softDelete(UUID restaurantId, UUID noteId, UUID actorId, Instant deletedAt);
}
