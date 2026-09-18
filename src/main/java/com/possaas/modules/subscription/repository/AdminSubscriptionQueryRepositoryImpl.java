package com.possaas.modules.subscription.repository;

import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@RequiredArgsConstructor
public class AdminSubscriptionQueryRepositoryImpl implements AdminSubscriptionQueryRepository {
    private final EntityManager entityManager;

    @Override
    public Page<RestaurantSubscription> search(
        AdminSubscriptionCriteria criteria,
        Instant now,
        Pageable pageable
    ) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<RestaurantSubscription> contentQuery = builder.createQuery(RestaurantSubscription.class);
        Root<RestaurantSubscription> subscription = contentQuery.from(RestaurantSubscription.class);
        Root<Restaurant> restaurant = contentQuery.from(Restaurant.class);
        Root<PackagePlan> packagePlan = contentQuery.from(PackagePlan.class);
        List<Predicate> predicates = predicates(
            builder,
            subscription,
            restaurant,
            packagePlan,
            criteria,
            now
        );
        contentQuery.select(subscription)
            .where(predicates.toArray(Predicate[]::new))
            .orderBy(orders(builder, subscription, pageable.getSort()));

        TypedQuery<RestaurantSubscription> typedQuery = entityManager.createQuery(contentQuery);
        typedQuery.setFirstResult(Math.toIntExact(pageable.getOffset()));
        typedQuery.setMaxResults(pageable.getPageSize());
        List<RestaurantSubscription> content = typedQuery.getResultList();

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<RestaurantSubscription> countSubscription = countQuery.from(RestaurantSubscription.class);
        Root<Restaurant> countRestaurant = countQuery.from(Restaurant.class);
        Root<PackagePlan> countPackage = countQuery.from(PackagePlan.class);
        countQuery.select(builder.count(countSubscription)).where(
            predicates(
                builder,
                countSubscription,
                countRestaurant,
                countPackage,
                criteria,
                now
            ).toArray(Predicate[]::new)
        );
        long total = entityManager.createQuery(countQuery).getSingleResult();
        return new PageImpl<>(content, pageable, total);
    }

    private static List<Predicate> predicates(
        CriteriaBuilder builder,
        Root<RestaurantSubscription> subscription,
        Root<Restaurant> restaurant,
        Root<PackagePlan> packagePlan,
        AdminSubscriptionCriteria criteria,
        Instant now
    ) {
        List<Predicate> result = new ArrayList<>();
        result.add(builder.equal(restaurant.get("id"), subscription.get("restaurantId")));
        result.add(builder.equal(packagePlan.get("id"), subscription.get("packageId")));
        result.add(builder.isNull(restaurant.get("deletedAt")));

        if (criteria.restaurantId() != null) {
            result.add(builder.equal(subscription.get("restaurantId"), criteria.restaurantId()));
        }
        if (criteria.status() != null) {
            result.add(builder.equal(subscription.get("status"), criteria.status()));
        }
        if (criteria.packageCode() != null && !criteria.packageCode().isBlank()) {
            result.add(builder.equal(
                builder.upper(packagePlan.get("code")),
                criteria.packageCode().trim().toUpperCase(Locale.ROOT)
            ));
        }
        if (criteria.query() != null && !criteria.query().isBlank()) {
            String pattern = "%" + criteria.query().trim().toLowerCase(Locale.ROOT) + "%";
            result.add(builder.or(
                builder.like(builder.lower(restaurant.get("code")), pattern),
                builder.like(builder.lower(restaurant.get("name")), pattern)
            ));
        }
        if (criteria.effective() != null) {
            Predicate effective = builder.and(
                builder.equal(subscription.get("status"), SubscriptionStatus.ACTIVE),
                builder.lessThanOrEqualTo(subscription.get("startAt"), now),
                builder.greaterThan(subscription.get("endAt"), now)
            );
            result.add(criteria.effective() ? effective : builder.not(effective));
        }
        return result;
    }

    private static List<Order> orders(
        CriteriaBuilder builder,
        Root<RestaurantSubscription> subscription,
        Sort sort
    ) {
        List<Order> orders = new ArrayList<>();
        sort.forEach(item -> {
            Expression<?> expression = switch (item.getProperty()) {
                case "createdAt" -> subscription.get("createdAt");
                case "startAt" -> subscription.get("startAt");
                case "endAt" -> subscription.get("endAt");
                case "status" -> subscription.get("status");
                case "priceAmount" -> subscription.get("priceAmount");
                case "id" -> subscription.get("id");
                default -> throw new IllegalArgumentException("Unsupported subscription sort property");
            };
            orders.add(item.isAscending() ? builder.asc(expression) : builder.desc(expression));
        });
        return orders;
    }
}
