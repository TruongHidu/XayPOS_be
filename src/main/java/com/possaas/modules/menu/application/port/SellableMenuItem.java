package com.possaas.modules.menu.application.port;

import java.math.BigDecimal;
import java.util.UUID;

/** Authoritative menu data for snapshotting, without cost or inventory internals. */
public record SellableMenuItem(UUID id, String name, String unit, BigDecimal salePrice) {}
