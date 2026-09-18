package com.possaas.modules.subscription.repository;

import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantSubscriptionRepository extends
    JpaRepository<RestaurantSubscription, UUID>,
    AdminSubscriptionQueryRepository {
    Optional<RestaurantSubscription> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select s from RestaurantSubscription s
        where s.id = :subscriptionId and s.restaurantId = :restaurantId
        """)
    Optional<RestaurantSubscription> findByIdAndRestaurantIdForUpdate(
        @Param("subscriptionId") UUID subscriptionId,
        @Param("restaurantId") UUID restaurantId
    );

    Page<RestaurantSubscription> findAllByRestaurantIdOrderByCreatedAtDesc(UUID restaurantId, Pageable pageable);

    Optional<RestaurantSubscription> findByRestaurantIdAndStatus(UUID restaurantId, SubscriptionStatus status);

    @Query("""
        select s from RestaurantSubscription s
        where s.restaurantId = :restaurantId
          and s.status = com.possaas.modules.subscription.entity.SubscriptionStatus.ACTIVE
          and s.startAt <= :now
          and :now < s.endAt
        """)
    Optional<RestaurantSubscription> findEffectiveSubscription(
        @Param("restaurantId") UUID restaurantId,
        @Param("now") Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select s from RestaurantSubscription s
        where s.restaurantId = :restaurantId
          and s.status = com.possaas.modules.subscription.entity.SubscriptionStatus.ACTIVE
        """)
    Optional<RestaurantSubscription> findActiveForUpdate(@Param("restaurantId") UUID restaurantId);

    @Query(
        value = """
            select *
            from restaurant_subscriptions
            where status = 'ACTIVE' and end_at <= :now
            order by end_at, id
            limit :batchSize
            for update skip locked
            """,
        nativeQuery = true
    )
    List<RestaurantSubscription> findDueActiveForUpdate(
        @Param("now") Instant now,
        @Param("batchSize") int batchSize
    );
}
