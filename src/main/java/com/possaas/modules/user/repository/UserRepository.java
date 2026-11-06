package com.possaas.modules.user.repository;

import com.possaas.modules.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByIdAndRestaurantIdAndDeletedAtIsNull(UUID id, UUID restaurantId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id and u.restaurantId = :restaurantId and u.deletedAt is null")
    Optional<User> findTenantUserForUpdate(@org.springframework.data.repository.query.Param("id") UUID id,
        @org.springframework.data.repository.query.Param("restaurantId") UUID restaurantId);

    @org.springframework.data.jpa.repository.Query("select count(u) from User u, Role r where u.roleId = r.id and u.restaurantId = :restaurantId and u.deletedAt is null and u.active = true and r.code in ('MANAGER', 'WAITER', 'KITCHEN', 'CASHIER')")
    long countActiveStaff(@org.springframework.data.repository.query.Param("restaurantId") UUID restaurantId);

    boolean existsByEmailAndIdNot(String email, UUID id);
    Optional<User> findByEmailAndDeletedAtIsNull(String email);
    Optional<User> findByIdAndDeletedAtIsNull(UUID id);
    boolean existsByEmail(String email);
}
