package com.possaas.modules.menu.dto;

import static com.possaas.modules.menu.service.MenuItemValidationPolicy.invalid;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Locale;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Getter
@Setter
public class RecipeSearch {
    @Min(0)
    private int page = 0;

    @Min(1)
    @Max(100)
    private int size = 20;

    private String sortBy = "recipeVersion";
    private String direction = "desc";

    public Pageable pageable() {
        Set<String> allowed = Set.of("recipeVersion", "createdAt", "updatedAt");
        if (sortBy == null || !allowed.contains(sortBy) || direction == null
                || !Set.of("asc", "desc").contains(direction.toLowerCase(Locale.ROOT)))
            throw invalid("Invalid pagination or sorting");
        return PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(direction), sortBy).and(Sort.by("id")));
    }
}
