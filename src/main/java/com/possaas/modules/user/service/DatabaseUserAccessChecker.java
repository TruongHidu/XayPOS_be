package com.possaas.modules.user.service;
import com.possaas.modules.user.application.port.UserAccessChecker;
import com.possaas.modules.user.repository.UserRepository;
import com.possaas.modules.user.entity.User;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@RequiredArgsConstructor
public class DatabaseUserAccessChecker implements UserAccessChecker {
    private final UserRepository users;
    @Override @Transactional(readOnly = true)
    public boolean isActive(UUID userId, UUID restaurantId) {
        return users.findByIdAndRestaurantIdAndDeletedAtIsNull(userId, restaurantId).filter(User::isActive).isPresent();
    }
}
