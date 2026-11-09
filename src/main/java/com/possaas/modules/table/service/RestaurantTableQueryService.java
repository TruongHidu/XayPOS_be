package com.possaas.modules.table.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.config.PublicMenuProperties;
import com.possaas.infrastructure.qrcode.*;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.table.dto.*;
import com.possaas.modules.table.entity.RestaurantTable;
import com.possaas.modules.table.repository.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantTableQueryService {
    static final String CARD_CAPTION = "Quét mã để xem menu & gọi món";

    private final TableAccessService access;
    private final RestaurantTableRepository tables;
    private final TableResponseMapper mapper;
    private final RestaurantRepository restaurants;
    private final PublicMenuProperties menu;
    private final QrCodeRenderer codes;
    private final QrCardRenderer cards;

    public PageResponse<RestaurantTableResponse> search(TableSearch.Tables search) {
        var actor = access.require(false);
        if (search.getAreaId() != null) access.area(actor.restaurantId(), search.getAreaId(), false);
        var page = tables.findAll(TableSpecifications.tables(actor.restaurantId(), search), search.pageable());
        return new PageResponse<>(mapper.tables(actor.restaurantId(), page.getContent()), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public RestaurantTableResponse detail(UUID id) {
        return mapper.table(access.table(access.require(false).restaurantId(), id, false));
    }

    public TableQrResponse qr(UUID id) {
        return TableQrResponse.from(access.table(access.require(false).restaurantId(), id, false), menu);
    }

    /** Bare QR code encoding the absolute table menu URL. Rendered on demand; nothing is stored. */
    public QrImage qrImage(UUID id, QrImageFormat format, int size) {
        var table = access.table(access.require(false).restaurantId(), id, false);
        String url = url(table);
        String name = "qr-ban-" + QrImage.slug(table.getCode(), "table");
        return format == QrImageFormat.SVG ? QrImage.svg(codes.svg(url, size), name)
                : QrImage.png(codes.png(url, size), name);
    }

    /** Printable card: restaurant name, QR, table name and code. */
    public QrImage qrCard(UUID id) {
        var tenant = access.require(false).restaurantId();
        var table = access.table(tenant, id, false);
        var restaurant = restaurants.findByIdAndDeletedAtIsNull(tenant)
                .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        String detail = table.getCode().equalsIgnoreCase(table.getName()) ? null : "Mã bàn: " + table.getCode();
        var card = new QrCardRenderer.QrCard(restaurant.getName(), table.getName(), detail, CARD_CAPTION, url(table));
        return QrImage.png(cards.render(card), "the-qr-ban-" + QrImage.slug(table.getCode(), "table"));
    }

    private String url(RestaurantTable table) {
        return menu.absolute(TableQrResponse.path(table.getQrToken()));
    }
}
