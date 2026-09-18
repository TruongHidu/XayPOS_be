package com.possaas.modules.subscription.dto;

import java.util.UUID;

public record AdminPackageReferenceResponse(
    UUID id,
    String code,
    String name
) {}
