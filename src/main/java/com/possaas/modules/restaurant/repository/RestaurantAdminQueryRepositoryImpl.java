package com.possaas.modules.restaurant.repository;

import com.possaas.modules.restaurant.dto.AdminRestaurantDetailResponse;
import com.possaas.modules.restaurant.dto.AdminRestaurantOwnerResponse;
import com.possaas.modules.restaurant.dto.AdminRestaurantSummaryResponse;
import com.possaas.modules.restaurant.dto.AdminSubscriptionBriefResponse;
import com.possaas.modules.restaurant.dto.RestaurantUserCountsResponse;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RestaurantAdminQueryRepositoryImpl implements RestaurantAdminQueryRepository {
    private static final String SUBSCRIPTION_SELECT = """
        select s.id as id,
               s.restaurantId as restaurantId,
               p.code as packageCode,
               s.status as status,
               s.startAt as startAt,
               s.endAt as endAt,
               s.autoRenew as autoRenew
          from RestaurantSubscription s, PackagePlan p
         where s.packageId = p.id
        """;

    private final EntityManager entityManager;

    @Override
    public Page<AdminRestaurantSummaryResponse> search(
        RestaurantAdminCriteria criteria,
        Instant now
    ) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        long total = count(criteria, builder);
        PageRequest pageRequest = PageRequest.of(criteria.page(), criteria.size());
        long offset = pageRequest.getOffset();
        if (offset >= total) {
            return new PageImpl<>(List.of(), pageRequest, total);
        }

        CriteriaQuery<Restaurant> query = builder.createQuery(Restaurant.class);
        Root<Restaurant> restaurant = query.from(Restaurant.class);
        query.select(restaurant)
            .where(predicates(criteria, builder, restaurant).toArray(Predicate[]::new))
            .orderBy(orderBy(criteria, builder, restaurant));

        List<Restaurant> restaurants = entityManager.createQuery(query)
            .setFirstResult(Math.toIntExact(offset))
            .setMaxResults(criteria.size())
            .getResultList();
        Map<UUID, AdminSubscriptionBriefResponse> effectiveSubscriptions =
            findEffectiveSubscriptions(restaurants.stream().map(Restaurant::getId).toList(), now);
        List<AdminRestaurantSummaryResponse> content = restaurants.stream()
            .map(item -> toSummary(item, effectiveSubscriptions.get(item.getId())))
            .toList();
        return new PageImpl<>(content, pageRequest, total);
    }

    @Override
    public Optional<AdminRestaurantDetailResponse> findDetail(UUID restaurantId, Instant now) {
        List<Restaurant> restaurants = entityManager.createQuery("""
            select r
              from Restaurant r
             where r.id = :restaurantId
               and r.deletedAt is null
            """, Restaurant.class)
            .setParameter("restaurantId", restaurantId)
            .setMaxResults(1)
            .getResultList();
        if (restaurants.isEmpty()) {
            return Optional.empty();
        }

        Restaurant restaurant = restaurants.getFirst();
        List<AdminRestaurantOwnerResponse> owners = findOwners(restaurantId);
        RestaurantUserCountsResponse userCounts = findUserCounts(restaurantId);
        AdminSubscriptionBriefResponse effectiveSubscription =
            findEffectiveSubscription(restaurantId, now).orElse(null);
        AdminSubscriptionBriefResponse latestSubscription =
            findLatestSubscription(restaurantId).orElse(null);

        return Optional.of(new AdminRestaurantDetailResponse(
            restaurant.getId(),
            restaurant.getCode(),
            restaurant.getName(),
            restaurant.getLegalName(),
            restaurant.getPhone(),
            restaurant.getAddress(),
            restaurant.getTimezone(),
            restaurant.getCurrencyCode(),
            restaurant.getStatus(),
            restaurant.getCreatedAt(),
            restaurant.getUpdatedAt(),
            owners,
            userCounts,
            effectiveSubscription,
            latestSubscription
        ));
    }

    private long count(RestaurantAdminCriteria criteria, CriteriaBuilder builder) {
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<Restaurant> restaurant = query.from(Restaurant.class);
        query.select(builder.count(restaurant))
            .where(predicates(criteria, builder, restaurant).toArray(Predicate[]::new));
        return entityManager.createQuery(query).getSingleResult();
    }

    private static List<Predicate> predicates(
        RestaurantAdminCriteria criteria,
        CriteriaBuilder builder,
        Root<Restaurant> restaurant
    ) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.isNull(restaurant.get("deletedAt")));
        if (criteria.status() != null) {
            predicates.add(builder.equal(restaurant.get("status"), criteria.status()));
        }
        if (criteria.query() != null) {
            String pattern = "%" + escapeLike(criteria.query()) + "%";
            predicates.add(builder.or(
                builder.like(builder.lower(restaurant.get("code")), pattern, '\\'),
                builder.like(builder.lower(restaurant.get("name")), pattern, '\\'),
                builder.like(builder.lower(restaurant.get("legalName")), pattern, '\\'),
                builder.like(builder.lower(restaurant.get("phone")), pattern, '\\')
            ));
        }
        return predicates;
    }

    private static List<Order> orderBy(
        RestaurantAdminCriteria criteria,
        CriteriaBuilder builder,
        Root<Restaurant> restaurant
    ) {
        boolean ascending = criteria.direction() == RestaurantAdminCriteria.Direction.ASC;
        Order primary = ascending
            ? builder.asc(restaurant.get(criteria.sortBy().property()))
            : builder.desc(restaurant.get(criteria.sortBy().property()));
        Order tieBreaker = ascending
            ? builder.asc(restaurant.get("id"))
            : builder.desc(restaurant.get("id"));
        return List.of(primary, tieBreaker);
    }

    private Map<UUID, AdminSubscriptionBriefResponse> findEffectiveSubscriptions(
        List<UUID> restaurantIds,
        Instant now
    ) {
        if (restaurantIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Tuple> rows = entityManager.createQuery(SUBSCRIPTION_SELECT + """
               and s.restaurantId in :restaurantIds
               and s.status = :status
               and s.startAt <= :now
               and s.endAt > :now
            """, Tuple.class)
            .setParameter("restaurantIds", restaurantIds)
            .setParameter("status", SubscriptionStatus.ACTIVE)
            .setParameter("now", now)
            .getResultList();
        Map<UUID, AdminSubscriptionBriefResponse> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(
            row.get("restaurantId", UUID.class),
            toSubscription(row)
        ));
        return result;
    }

    private Optional<AdminSubscriptionBriefResponse> findEffectiveSubscription(
        UUID restaurantId,
        Instant now
    ) {
        return firstSubscription(entityManager.createQuery(SUBSCRIPTION_SELECT + """
               and s.restaurantId = :restaurantId
               and s.status = :status
               and s.startAt <= :now
               and s.endAt > :now
             order by s.createdAt desc, s.id desc
            """, Tuple.class)
            .setParameter("restaurantId", restaurantId)
            .setParameter("status", SubscriptionStatus.ACTIVE)
            .setParameter("now", now));
    }

    private Optional<AdminSubscriptionBriefResponse> findLatestSubscription(UUID restaurantId) {
        return firstSubscription(entityManager.createQuery(SUBSCRIPTION_SELECT + """
               and s.restaurantId = :restaurantId
             order by s.createdAt desc, s.id desc
            """, Tuple.class)
            .setParameter("restaurantId", restaurantId));
    }

    private static Optional<AdminSubscriptionBriefResponse> firstSubscription(
        TypedQuery<Tuple> query
    ) {
        List<Tuple> rows = query.setMaxResults(1).getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(toSubscription(rows.getFirst()));
    }

    private List<AdminRestaurantOwnerResponse> findOwners(UUID restaurantId) {
        return entityManager.createQuery("""
            select new com.possaas.modules.restaurant.dto.AdminRestaurantOwnerResponse(
                u.id, u.name, u.email, u.phone, u.active, u.lastLoginAt
            )
              from User u, Role r
             where u.roleId = r.id
               and u.restaurantId = :restaurantId
               and u.deletedAt is null
               and r.code = 'OWNER'
               and r.active = true
             order by u.name asc, u.email asc, u.id asc
            """, AdminRestaurantOwnerResponse.class)
            .setParameter("restaurantId", restaurantId)
            .getResultList();
    }

    private RestaurantUserCountsResponse findUserCounts(UUID restaurantId) {
        Object[] values = entityManager.createQuery("""
            select count(u),
                   coalesce(sum(case when u.active = true then 1 else 0 end), 0)
              from User u
             where u.restaurantId = :restaurantId
               and u.deletedAt is null
            """, Object[].class)
            .setParameter("restaurantId", restaurantId)
            .getSingleResult();
        return new RestaurantUserCountsResponse(
            ((Number) values[0]).longValue(),
            ((Number) values[1]).longValue()
        );
    }

    private static AdminSubscriptionBriefResponse toSubscription(Tuple row) {
        return new AdminSubscriptionBriefResponse(
            row.get("id", UUID.class),
            row.get("packageCode", String.class),
            row.get("status", SubscriptionStatus.class),
            row.get("startAt", Instant.class),
            row.get("endAt", Instant.class),
            row.get("autoRenew", Boolean.class)
        );
    }

    private static AdminRestaurantSummaryResponse toSummary(
        Restaurant restaurant,
        AdminSubscriptionBriefResponse effectiveSubscription
    ) {
        return new AdminRestaurantSummaryResponse(
            restaurant.getId(),
            restaurant.getCode(),
            restaurant.getName(),
            restaurant.getLegalName(),
            restaurant.getPhone(),
            restaurant.getTimezone(),
            restaurant.getCurrencyCode(),
            restaurant.getStatus(),
            restaurant.getCreatedAt(),
            restaurant.getUpdatedAt(),
            effectiveSubscription
        );
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
