package com.possaas.modules.admin.application.port;

import com.possaas.modules.admin.domain.SubscriptionStatistics;
import java.time.Instant;

@FunctionalInterface
public interface SubscriptionStatisticsQuery {
    SubscriptionStatistics getStatistics(Instant now);
}
