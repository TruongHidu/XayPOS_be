package com.possaas.modules.restaurant.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminRestaurantNoteResponse(UUID id, String content, UUID authorId, String authorName, Instant createdAt) {}
