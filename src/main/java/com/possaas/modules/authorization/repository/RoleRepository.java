package com.possaas.modules.authorization.repository;

import com.possaas.modules.authorization.entity.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findByCodeAndRestaurantIdIsNull(String code);
    Optional<Role> findByCodeAndRestaurantId(String code, UUID restaurantId);
}
