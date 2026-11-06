package com.possaas.modules.user.application.port;
import java.util.UUID;
public interface UserAccessChecker { boolean isActive(UUID userId, UUID restaurantId); }
