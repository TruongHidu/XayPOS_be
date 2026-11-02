package com.possaas.modules.restaurant.repository;

import com.possaas.modules.restaurant.entity.Restaurant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface RestaurantRepository extends JpaRepository<Restaurant, UUID> {
    boolean existsByCode(String code);
    Optional<Restaurant> findByCodeAndDeletedAtIsNull(String code);
    Optional<Restaurant> findByIdAndDeletedAtIsNull(UUID id);
    Optional<Restaurant> findByPublicOrderToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Restaurant r where r.id = :id and r.deletedAt is null")
    Optional<Restaurant> findByIdForUpdate(@Param("id") UUID id);

    /** Caller holds the restaurant lock. An MVCC write makes a waiting RR buyer retry its old snapshot. */
    @Modifying
    @Query(value = "update restaurants set id = id where id = :id", nativeQuery = true)
    int touchSubscriptionCoordination(@Param("id") UUID id);
}
