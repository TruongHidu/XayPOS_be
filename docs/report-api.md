# Operational report API

Module REPORT is tenant-only and read-only. All endpoints require a staff JWT, use the restaurant from the token, and return `Cache-Control: no-store`. They never accept a restaurant ID and are not available to the public QR portal or a system administrator without tenant context.

V38 adds a separate ORDER-owned [historical reference-cost summary](order-reference-cost-snapshot-api.md),
requiring ORDER_VIEW + REPORT_DETAIL_VIEW and ORDER_MANAGEMENT + DETAIL_REPORT.
That endpoint is outside `/reports` and returns no recipe/ingredient details.
Existing report totals and access requirements below stay unchanged. The separate
[reference-margin reports](reference-margin-report-api.md) use frozen historical
reference cost; they are not actual COGS or net profit.

## Access matrix

| Endpoint | Permission | Effective subscription feature |
|---|---|---|
| `GET /api/v1/reports/daily` | `REPORT_DAILY_VIEW` | `DAILY_REVENUE` |
| Other operational endpoints below | `REPORT_DETAIL_VIEW` | `DETAIL_REPORT` |
| `/api/v1/reports/reference-margin/*` | `ORDER_VIEW` and `REPORT_DETAIL_VIEW` | `ORDER_MANAGEMENT` and `DETAIL_REPORT` |

Authorization is permission- and feature-based, not role- or package-name-based. The feature comes from the immutable effective subscription snapshot. The operational endpoints do not additionally require `ORDER_VIEW` or `PAYMENT_VIEW`; reference-margin endpoints do require `ORDER_VIEW`.

Common headers:

```http
Authorization: Bearer <accessToken>
Accept: application/json
```

Common optional query parameters:

- `currencyCode`: ISO 4217 alphabetic code. Defaults to the restaurant's current currency.
- `serviceType`: `DINE_IN` or `TAKEAWAY`.
- `sourceChannel`: `CASHIER`, `WAITER`, `QR_STATIC`, or `QR_TABLE`.

`sourceChannel` means the channel that created the root order. It does not identify the source of every line appended later.

## Date and money rules

`fromDate` and `toDate` are inclusive calendar dates in the restaurant's current timezone. Internally the API uses `[start of fromDate, start of day after toDate)` and never an inclusive end-of-day timestamp. The maximum range is 366 calendar days. Changing a restaurant timezone can change how historical timestamps are grouped.

All amounts in one response use `meta.currencyCode`; the server never converts or mixes currencies. Query another historical currency explicitly with `currencyCode`.

- `collectedAmount`: sum of `payments.amount` where payment status is `SUCCESS`, attributed by `paidAt`. The order does not need to be completed.
- `successfulPaymentCount`: number of matching successful payment rows.
- `paidOrderCount`: distinct matching payment order IDs.
- `completedOrderSalesAmount`: sum of completed order totals, attributed by `completedAt`.
- created/cancelled counts use `createdAt`/`cancelledAt` respectively.

Because these facts use different business timestamps, collected amount can differ from completed-order sales, and created count is not expected to equal completed plus cancelled count.

## Response envelope

```json
{
  "meta": {
    "fromDate": "2026-10-15",
    "toDate": "2026-10-15",
    "timezone": "Asia/Ho_Chi_Minh",
    "currencyCode": "VND",
    "generatedAt": "2026-10-15T12:00:00Z",
    "serviceType": null,
    "sourceChannel": null,
    "granularity": null,
    "dateBasis": null
  },
  "data": {}
}
```

Empty ranges return HTTP 200 with zero amounts/counts and empty lists.

## Daily report

```http
GET /api/v1/reports/daily?date=2026-10-15&currencyCode=VND
```

`date` is optional and defaults to today according to the restaurant timezone and the application clock.

```json
{
  "meta": { "fromDate": "2026-10-15", "toDate": "2026-10-15", "timezone": "Asia/Ho_Chi_Minh", "currencyCode": "VND", "generatedAt": "2026-10-15T12:00:00Z", "serviceType": null, "sourceChannel": null, "granularity": null, "dateBasis": null },
  "data": {
    "collections": { "collectedAmount": 1500000.00, "successfulPaymentCount": 11, "paidOrderCount": 10 },
    "orders": {
      "createdOrderCount": 12,
      "completedOrderCount": 10,
      "cancelledOrderCount": 1,
      "completedSubtotalAmount": 1450000.00,
      "completedDiscountAmount": 50000.00,
      "completedTaxAmount": 0.00,
      "completedServiceChargeAmount": 0.00,
      "completedOrderSalesAmount": 1400000.00,
      "averageCompletedOrderValue": 140000.00
    }
  }
}
```

## Range summary

```http
GET /api/v1/reports/summary?fromDate=2026-10-01&toDate=2026-10-15
```

Uses the same data schema as daily. Both dates are required.

## Revenue series

```http
GET /api/v1/reports/revenue-series?fromDate=2026-10-01&toDate=2026-10-15&granularity=DAY
```

`granularity` is `DAY` (default) or `MONTH`. Missing buckets are returned with zero values. Month buckets use the first calendar date of each month; the first and last month are clipped by the requested range.

```json
{
  "meta": { "fromDate": "2026-10-01", "toDate": "2026-10-15", "timezone": "Asia/Ho_Chi_Minh", "currencyCode": "VND", "generatedAt": "2026-10-15T12:00:00Z", "serviceType": null, "sourceChannel": null, "granularity": "DAY", "dateBasis": null },
  "data": [
    { "bucketStartDate": "2026-10-01", "collectedAmount": 100000.00, "completedOrderSalesAmount": 80000.00, "successfulPaymentCount": 2, "completedOrderCount": 1 }
  ]
}
```

## Payment methods

```http
GET /api/v1/reports/payment-methods?fromDate=2026-10-01&toDate=2026-10-15
```

Always returns `CASH` and `TRANSFER`, including zero rows. Percentage is based on collected amount and rounded to two decimals.

```json
{
  "meta": { "fromDate": "2026-10-01", "toDate": "2026-10-15", "timezone": "Asia/Ho_Chi_Minh", "currencyCode": "VND", "generatedAt": "2026-10-15T12:00:00Z", "serviceType": null, "sourceChannel": null, "granularity": null, "dateBasis": null },
  "data": [
    { "paymentMethod": "CASH", "collectedAmount": 600000.00, "successfulPaymentCount": 6, "percentage": 60.00 },
    { "paymentMethod": "TRANSFER", "collectedAmount": 400000.00, "successfulPaymentCount": 4, "percentage": 40.00 }
  ]
}
```

## Top items

```http
GET /api/v1/reports/top-items?fromDate=2026-10-01&toDate=2026-10-15&page=0&size=20&sortBy=quantity&direction=desc
```

- `sortBy`: `quantity` (default) or `itemSalesAmount`.
- `direction`: `asc` or `desc`.
- `size`: 1–100.

Only non-cancelled lines of completed orders are included, attributed by order `completedAt`. Rows are grouped by item ID plus snapshot name and unit. A null item ID remains reportable using snapshot name/unit. Historical menu deletion or rename does not rewrite results.

```json
{
  "meta": { "fromDate": "2026-10-01", "toDate": "2026-10-15", "timezone": "Asia/Ho_Chi_Minh", "currencyCode": "VND", "generatedAt": "2026-10-15T12:00:00Z", "serviceType": null, "sourceChannel": null, "granularity": null, "dateBasis": null },
  "data": {
    "content": [ { "itemId": null, "itemName": "Phở", "unit": "portion", "quantity": 12.000, "itemSalesAmount": 600000.00, "completedOrderCount": 9 } ],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
  }
}
```

`itemSalesAmount` is not profit and may not equal bill totals because order-level discounts, tax, and service charge are not allocated to lines. Historical cost snapshots are available through the separate reference-cost API and reports; they do not change this endpoint's semantics.

## Reconciliation order list

```http
GET /api/v1/reports/orders?fromDate=2026-10-01&toDate=2026-10-15&dateBasis=PAID&page=0&size=20
```

- `dateBasis`: `CREATED` (default), `COMPLETED`, `CANCELLED`, `PAID`.
- Optional root filters: `status`, `paymentStatus`.
- `sortBy`: `reportEventAt` (default) or `totalAmount`.
- `direction`: `asc` or `desc`.

For `PAID`, an order appears once even when it has several successful payments. `reportEventAt` is the latest matching `paidAt`, and `collectedAmountInRange` is the sum of that order's successful payments in the requested window and currency.

The row allowlist deliberately excludes customer identity, notes, provider metadata, and private order detail.

## Errors

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `INVALID_REPORT_DATE_RANGE` | Missing/descending invalid date range |
| 400 | `REPORT_RANGE_TOO_LARGE` | More than 366 inclusive days |
| 400 | `INVALID_REPORT_CURRENCY` | Unsupported ISO currency input |
| 400 | `INVALID_REPORT_SORT` | Unsupported report sort |
| 400 | `VALIDATION_ERROR` | Invalid enum, page, size, or parameter |
| 401 | `UNAUTHORIZED` | Missing/invalid staff JWT |
| 403 | `FORBIDDEN` | Missing report permission |
| 403 | `TENANT_ACCESS_DENIED` | No tenant context |
| 403 | `FEATURE_NOT_ENTITLED` | Effective snapshot lacks feature |
| 403 | `SUBSCRIPTION_NOT_ACTIVE` | No effective active subscription |
| 409 | `INVALID_REPORT_TIMEZONE` | Stored restaurant timezone is invalid |

No refund, actual COGS, net-profit, tax-accounting, split-payment, inventory, staff-performance, export, or AI report is implemented in this MVP. See [reference-margin reports](reference-margin-report-api.md) for the historical reference-cost cohort.
