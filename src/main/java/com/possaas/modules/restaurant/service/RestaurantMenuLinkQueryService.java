package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.config.PublicMenuProperties;
import com.possaas.infrastructure.qrcode.*;
import com.possaas.modules.restaurant.dto.RestaurantMenuLinkResponse;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantMenuLinkQueryService {
    static final String CARD_SUBTITLE = "Menu nhà hàng";
    static final String CARD_CAPTION = "Quét mã để xem menu & đặt món mang đi";

    private final CurrentTenantProvider tenant;
    private final RestaurantRepository restaurants;
    private final PublicMenuProperties menu;
    private final QrCodeRenderer codes;
    private final QrCardRenderer cards;

    public RestaurantMenuLinkResponse get() {
        return RestaurantMenuLinkResponse.fromToken(initialized().getPublicOrderToken(), menu);
    }

    /** Bare QR for the shared restaurant menu link. Rendered on demand; nothing is stored. */
    public QrImage qrImage(QrImageFormat format, int size) {
        String url = url(initialized());
        return format == QrImageFormat.SVG ? QrImage.svg(codes.svg(url, size), "qr-menu")
                : QrImage.png(codes.png(url, size), "qr-menu");
    }

    public QrImage qrCard() {
        var restaurant = initialized();
        var card = new QrCardRenderer.QrCard(restaurant.getName(), CARD_SUBTITLE, null, CARD_CAPTION, url(restaurant));
        return QrImage.png(cards.render(card), "the-qr-menu");
    }

    private Restaurant initialized() {
        var restaurant = restaurants.findByIdAndDeletedAtIsNull(tenant.getRequiredRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        if (restaurant.getPublicOrderToken() == null) throw notInitialized();
        return restaurant;
    }

    private String url(Restaurant restaurant) {
        return menu.absolute(RestaurantMenuLinkResponse.path(restaurant.getPublicOrderToken()));
    }

    static ResourceNotFoundException notInitialized() {
        return new ResourceNotFoundException("PUBLIC_MENU_LINK_NOT_INITIALIZED", "Menu link has not been initialized");
    }
}
