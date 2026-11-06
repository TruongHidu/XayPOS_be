package com.possaas.modules.menu.dto;

import com.possaas.modules.menu.entity.AvailabilityStatus;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.*;
import org.springframework.data.domain.*;
import static com.possaas.modules.menu.service.MenuItemValidationPolicy.invalid;

@Getter
@Setter
public class MenuSearch {
    @Size(max = 100)
    private String q;
    private Boolean active;
    private UUID groupId;
    private AvailabilityStatus availabilityStatus;
    private Boolean sellable;
    @Min(0)
    private int page = 0;
    @Min(1)
    @Max(100)
    private int size = 20;
    private String sortBy = "createdAt";
    private String direction = "desc";

    public Pageable pageable(boolean item) {
        var fields = item ? Set.of("createdAt", "name", "salePrice", "updatedAt")
                : Set.of("createdAt", "name", "displayOrder", "updatedAt");
        if (page < 0 || size < 1 || size > 100 || sortBy == null || !fields.contains(sortBy) || direction == null
                || !Set.of("asc", "desc").contains(direction.toLowerCase(Locale.ROOT)))
            throw invalid("Invalid pagination or sorting");
        return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(direction), sortBy).and(Sort.by("id")));
    }

    public String pattern() {
        return "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                + "%";
    }
}
