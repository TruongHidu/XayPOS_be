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
