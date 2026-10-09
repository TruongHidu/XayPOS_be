package com.possaas.modules.menu.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PublicQrMenuAccessPolicy {
    private final PublicMenuAccessPolicy access;

    public void requireAccess(UUID restaurantId) {
        access.requireAccess(restaurantId, "QR_MENU_UNAVAILABLE");
    }
}
