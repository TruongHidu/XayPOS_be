# REPORT frontend synchronization

This document is an integration hand-off only; the backend task does not implement Flutter or Web UI.

## Navigation and access

- Show the daily report capability only when the authenticated user has `REPORT_DAILY_VIEW` and the effective subscription has `DAILY_REVENUE`.
- Show detailed report screens only with `REPORT_DETAIL_VIEW` plus `DETAIL_REPORT`.
- Do not hardcode OWNER, CASHIER, BASIC, or PRO.
- A hidden button is not authorization; handle HTTP 403 from the backend.

## Labels

- Render `collectedAmount` as **Tiền đã thu**.
- Render `completedOrderSalesAmount` as **Doanh số đơn hoàn tất**.
- Do not merge these values or label both as one revenue value.
- Do not label `itemSalesAmount` as profit.
- Display `meta.timezone` and `meta.currencyCode` near active filters.

## State and cache keys

Every query key must include endpoint, dates, currency, service type, source channel, and endpoint-specific parameters (`granularity`, `dateBasis`, status, payment status, page, size, sort, direction). Never merge responses with different currencies.

The backend returns `Cache-Control: no-store`; keep report state short-lived and refresh explicitly after operational mutations such as payment or completion. Do not invoke report endpoints from public QR routes.

## Recommended screen split

1. Daily card screen: `/reports/daily`.
2. Detailed summary plus series: `/reports/summary` and `/reports/revenue-series`.
3. Payment composition: `/reports/payment-methods`.
4. Top-item table: `/reports/top-items`.
5. Reconciliation drawer/table: `/reports/orders`.

An empty report is a successful state: show zeros and an empty table, not a not-found error.

## Reference margin

The three [reference-margin endpoints](reference-margin-report-api.md) require both `ORDER_VIEW` and `REPORT_DETAIL_VIEW`, and both `ORDER_MANAGEMENT` and `DETAIL_REPORT`. Keep the ordinary report guards unchanged. Use backend errors as the final authority.

Show net sales for all completed orders separately from the covered net sales, reference cost, and reference margin for fully costed orders only. Display `costCoverageStatus`, fully costed/excluded counts and issue codes; incomplete, unavailable and legacy snapshots are unknown, not zero. If no orders qualify, covered sums are zero for the empty cohort and margin percentage is null. Do not label the result net profit or actual COGS.

Summary and series use `completedAt` in the restaurant timezone. Series buckets are zero-filled; use the server's total percentage rather than averaging bucket percentages. The order list allows `costCoverage` and paginated sorting; null cost/margin means the order is excluded. Add `costCoverage`, `granularity`, dates, currency, service type, source channel, page, size, sort and direction to the respective cache keys. A 409 `REPORT_COST_DATA_CONFLICT` needs a recoverable error state, not a fabricated zero.
