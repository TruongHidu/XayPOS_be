package com.possaas.modules.user.dto;

import java.util.UUID;

public record AdminRoleSummaryResponse(UUID id, String code, String name, boolean active) {}
