package com.possaas.modules.menu.repository;

import com.possaas.modules.menu.dto.PublicMenuSearch;
import com.possaas.modules.menu.entity.*;
import jakarta.persistence.criteria.Predicate;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;

public final class PublicMenuSpecifications {
    private PublicMenuSpecifications() {}

    public static Specification<ItemGroup> groups(UUID tenant) {
        return (r, q, b) -> b.and(b.equal(r.get("restaurantId"), tenant),
                b.isTrue(r.get("active")), b.isNull(r.get("deletedAt")));
    }

    public static Specification<Item> items(UUID tenant, PublicMenuSearch search, Set<AvailabilityStatus> statuses) {
        return (r, q, b) -> {
            var group = q.subquery(UUID.class);
            var g = group.from(ItemGroup.class);
            group.select(g.get("id")).where(b.equal(g.get("restaurantId"), tenant),
                    b.equal(g.get("id"), r.get("groupId")), b.isTrue(g.get("active")), b.isNull(g.get("deletedAt")));
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(b.equal(r.get("restaurantId"), tenant));
            predicates.add(b.equal(r.get("itemType"), ItemType.MENU_ITEM));
            predicates.add(b.isNull(r.get("deletedAt")));
            predicates.add(b.isTrue(r.get("active")));
            predicates.add(r.get("availabilityStatus").in(statuses));
            predicates.add(b.or(b.isNull(r.get("groupId")), b.exists(group)));
            if (search.getGroupId() != null) predicates.add(b.equal(r.get("groupId"), search.getGroupId()));
            if (search.getQ() != null && !search.getQ().isBlank())
                predicates.add(b.like(b.lower(r.get("name")), search.pattern(), '\\'));
            return b.and(predicates.toArray(Predicate[]::new));
        };
    }
}
