package com.possaas.modules.table.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.possaas.modules.table.entity.TableStatus;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class TableRequests {
    private TableRequests() {}

    public record CreateArea(@NotBlank @Size(max = 100) String name,
            @Size(max = 2000) String description, @Min(0) Integer displayOrder, Boolean active) {}
    public record UpdateArea(@Size(max = 100) String name, @Size(max = 2000) String description,
            @Min(0) Integer displayOrder, @NotNull @Min(0) Long expectedVersion) {}
    public record AreaStatus(@NotNull Boolean active, @NotNull @Min(0) Long expectedVersion) {}
    public record CreateTable(UUID areaId, @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 100) String name, @Min(1) @Max(32767) Integer capacity,
            @Min(0) Integer displayOrder, TableStatus status) {}
    public record UpdateTable(@Size(max = 50) String code, @Size(max = 100) String name,
            @Min(1) @Max(32767) Integer capacity, @Min(0) Integer displayOrder,
            @NotNull @Min(0) Long expectedVersion) {}
    public record Status(@NotNull TableStatus status, @NotNull @Min(0) Long expectedVersion) {}
    public record AreaAssignment(@JsonProperty(value = "areaId", required = true) UUID areaId,
            @NotNull @Min(0) Long expectedVersion) {}
    public record Version(@NotNull @Min(0) Long expectedVersion) {}
    public record OpenSession(@Min(1) @Max(32767) Integer guestCount, @Size(max = 2000) String note,
            @NotNull @Min(0) Long expectedVersion) {}
    public record UpdateSession(@Min(1) @Max(32767) Integer guestCount, @Size(max = 2000) String note,
            @NotNull @Min(0) Long expectedVersion) {}
    public record CancelSession(@NotBlank @Size(max = 1000) String reason,
            @NotNull @Min(0) Long expectedVersion) {}
}
