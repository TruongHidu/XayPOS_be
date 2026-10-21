package com.possaas.modules.menu.dto;

import static com.possaas.modules.menu.service.MenuItemValidationPolicy.invalid;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Getter
@Setter
public class IngredientSearch {
    @Size(max = 100)
    private String q;
    private Boolean active;
    private Boolean trackInventory;
    @Min(0)
    private int page = 0;
    @Min(1)
    @Max(100)
    private int size = 20;
    private String sortBy = "createdAt";
    private String direction = "desc";

    public Pageable pageable() {
        var fields = Set.of("createdAt", "updatedAt", "name", "sku", "costPrice");
        if (sortBy == null || !fields.contains(sortBy) || direction == null
                || !Set.of("asc", "desc").contains(direction.toLowerCase(Locale.ROOT)))
            throw invalid("Invalid pagination or sorting");
        return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(direction), sortBy).and(Sort.by("id")));
    }

    public String pattern() {
        return "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }
}
