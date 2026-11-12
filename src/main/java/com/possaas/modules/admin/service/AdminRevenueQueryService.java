package com.possaas.modules.admin.service;

import com.possaas.modules.admin.application.port.RevenueStatisticsQuery;
import com.possaas.modules.admin.application.port.RevenueStatisticsQuery.MonthlyCount;
import com.possaas.modules.admin.application.port.RevenueStatisticsQuery.MonthlyRevenue;
import com.possaas.modules.admin.dto.AdminRevenueResponse;
import com.possaas.modules.admin.dto.AdminRevenueResponse.MonthPoint;
import com.possaas.modules.admin.dto.AdminRevenueResponse.PackageMix;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assembles the revenue dashboard: a gap-free monthly series plus recurring revenue, package mix and renewals. */
@Service
@RequiredArgsConstructor
public class AdminRevenueQueryService {
    public static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration CHURN_WINDOW = Duration.ofDays(30);
    private static final Duration EXPIRING_WINDOW = Duration.ofDays(14);
    private static final int EXPIRING_LIMIT = 10;

    private final RevenueStatisticsQuery statistics;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AdminRevenueResponse revenue(int months) {
        Instant now = clock.instant();
        YearMonth current = YearMonth.from(now.atZone(BILLING_ZONE));
        YearMonth first = current.minusMonths(months - 1L);
        Instant from = first.atDay(1).atStartOfDay(BILLING_ZONE).toInstant();

        List<MonthPoint> series = series(first, current,
            index(statistics.monthlyRevenue(from, now, BILLING_ZONE), MonthlyRevenue::month),
            index(statistics.newRestaurantsByMonth(from, now, BILLING_ZONE), MonthlyCount::month));
        var recurring = statistics.recurringRevenue(now);
        List<PackageMix> mix = statistics.packageMix(now).stream()
            .map(p -> new PackageMix(p.packageCode(), p.packageName(), p.restaurants(), money(p.mrr()))).toList();

        BigDecimal thisMonth = series.get(series.size() - 1).revenue();
        BigDecimal previous = series.size() > 1 ? series.get(series.size() - 2).revenue() : BigDecimal.ZERO;
        return new AdminRevenueResponse(now, "VND", BILLING_ZONE.getId(), money(recurring.mrr()), recurring.payingRestaurants(),
            thisMonth, previous, statistics.churnedRestaurants(now.minus(CHURN_WINDOW), now), series, mix,
            statistics.expiringRestaurants(now, now.plus(EXPIRING_WINDOW), EXPIRING_LIMIT));
    }

    private static List<MonthPoint> series(YearMonth first, YearMonth last, Map<String, MonthlyRevenue> revenue,
            Map<String, MonthlyCount> restaurants) {
        List<MonthPoint> points = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            String key = month.toString();
            MonthlyRevenue r = revenue.get(key);
            BigDecimal total = money(r == null ? null : r.revenue()), online = money(r == null ? null : r.onlineRevenue());
            points.add(new MonthPoint(key, total, online, total.subtract(online), r == null ? 0 : r.activatedSubscriptions(),
                restaurants.containsKey(key) ? restaurants.get(key).count() : 0));
        }
        return points;
    }

    private static <T> Map<String, T> index(List<T> rows, Function<T, String> month) {
        return rows.stream().collect(Collectors.toMap(month, Function.identity()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP);
    }
}
