package com.possaas.modules.restaurant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRestaurantNoteRequest(@NotBlank @Size(max = 2000) String content) {}
