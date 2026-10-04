package com.possaas.modules.menu.repository;

import com.possaas.modules.menu.dto.MenuSearch;
import com.possaas.modules.menu.entity.*;
import jakarta.persistence.criteria.Predicate;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;

public final class MenuSpecifications {
    private MenuSpecifications() {
    }

    public static Specification<ItemGroup> groups(UUID tenant, MenuSearch s) {
        return (r, q, b) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(b.equal(r.get("restaurantId"), tenant));
            p.add(b.isNull(r.get("deletedAt")));
            if (s.getActive() != null)
                p.add(b.equal(r.get("active"), s.getActive()));
            if (s.getQ() != null && !s.getQ().isBlank())
                p.add(b.like(b.lower(r.get("name")), s.pattern(), '\\'));
            return b.and(p.toArray(Predicate[]::new));
        };
    }

    public static Specification<Item> items(UUID tenant, MenuSearch s) {
        return (r, q, b) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(b.equal(r.get("restaurantId"), tenant));
            p.add(b.isNull(r.get("deletedAt")));
            p.add(b.equal(r.get("itemType"), ItemType.MENU_ITEM));
            if (s.getActive() != null)
                p.add(b.equal(r.get("active"), s.getActive()));
            if (s.getGroupId() != null)
                p.add(b.equal(r.get("groupId"), s.getGroupId()));
            if (s.getAvailabilityStatus() != null)
                p.add(b.equal(r.get("availabilityStatus"), s.getAvailabilityStatus()));
            if (s.getQ() != null && !s.getQ().isBlank())
                p.add(b.or(b.like(b.lower(r.get("name")), s.pattern(), '\\'),
                        b.like(b.lower(r.get("sku")), s.pattern(), '\\')));
            if (s.getSellable() != null) {
                var sub = q.subquery(UUID.class);
                var g = sub.from(ItemGroup.class);
                sub.select(g.get("id")).where(b.equal(g.get("restaurantId"), tenant),
                        b.equal(g.get("id"), r.get("groupId")), b.isTrue(g.get("active")),
                        b.isNull(g.get("deletedAt")));
                Predicate sellable = b.and(b.isTrue(r.get("active")),
                        b.equal(r.get("availabilityStatus"), AvailabilityStatus.AVAILABLE),
                        b.or(b.isNull(r.get("groupId")), b.exists(sub)));
                p.add(s.getSellable() ? sellable : b.not(sellable));
            }
            return b.and(p.toArray(Predicate[]::new));
        };
    }
}
