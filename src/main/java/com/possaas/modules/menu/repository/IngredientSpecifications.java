package com.possaas.modules.menu.repository;

import com.possaas.modules.menu.dto.IngredientSearch;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class IngredientSpecifications {
    private IngredientSpecifications() {
    }

    public static Specification<Item> ingredients(UUID restaurantId, IngredientSearch search) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("restaurantId"), restaurantId));
            predicates.add(builder.equal(root.get("itemType"), ItemType.INGREDIENT));
            predicates.add(builder.isNull(root.get("deletedAt")));
            if (search.getActive() != null)
                predicates.add(builder.equal(root.get("active"), search.getActive()));
            if (search.getTrackInventory() != null)
                predicates.add(builder.equal(root.get("trackInventory"), search.getTrackInventory()));
            if (search.getQ() != null && !search.getQ().isBlank())
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("name")), search.pattern(), '\\'),
                        builder.like(builder.lower(root.get("sku")), search.pattern(), '\\')));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
