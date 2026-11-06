package com.possaas.modules.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderItemTransitionRequest(@NotNull @Min(0) Long expectedOrderVersion) {}
