# Public QR menu API

Backend-only, read-only API for the customer menu website. OpenAPI: [public-qr-menu-api.yaml](openapi/public-qr-menu-api.yaml).

Public API paths use `/api/v1/public/menu/tables/{qrToken}` and its `/items` suffix. The former `/api/v1/public/qr-menu/*` paths are no longer exposed; the browser QR route `/qr/{qrToken}` is unchanged.

Menu chung nhà hàng/khách mang đi (không gắn bàn): [Public restaurant menu API](public-restaurant-menu-api.md). Hai luồng dùng chung món, visibility và pagination; token và context được resolve riêng.

Implementation files and actual verification results: [implementation report](public-qr-menu-implementation.md).

## Scope and flow

1. Staff creates a table using the existing authenticated TABLE API. The table already receives a cryptographically random `qrToken`.
2. The website URL printed as a QR code includes this table token. The website calls the two GET endpoints below.
3. Backend resolves the table and its restaurant from the token, checks availability and the effective subscription snapshot, and returns public fields only.

The token belongs to `restaurant_tables`, not `table_sessions`, a JWT, or `restaurants.public_order_token`. It is 43 base64url characters (32 random bytes). A table with no area is supported. An OPEN table session is **not** required; an occupied table is also supported. Viewing never opens/closes a session, creates an order, changes a subscription or records a business audit event.

Only `QR_MENU_VIEW` in the effective subscription's immutable feature snapshot is required. No staff account, permission, `MENU_MANAGEMENT` or TABLE feature is required. Subscription end time is exclusive: `endAt <= Clock.instant()` is unavailable even when its stored status still says ACTIVE. Reads do not reconcile the subscription or change snapshots. Package catalog edits do not retroactively grant access.

Table must be AVAILABLE and undeleted; its area, if any, must belong to the same restaurant and be active/undeleted. Restaurant must be ACTIVE and undeleted. Invalid, unknown, rotated or unavailable table links all return the same generic 404.

## Headers and request body

```http
Accept: application/json
```

No Authorization, restaurant ID header, cookies or request body are required. A supplied Bearer token is ignored for **these two GET routes only**, including expired/malformed tokens and tokens from another tenant. All existing internal routes retain authentication and permission checks. CORS continues to use the project's configured allowed origins; it is not changed to a wildcard.

Successful and controller-handled error responses carry `Cache-Control: no-store`. Do not store these responses in a service worker, CDN or application cache.

## GET /api/v1/public/menu/tables/{qrToken}

Example success (200):

```json
{
  "restaurant": { "name": "Nhà hàng Demo", "currencyCode": "VND" },
  "table": { "code": "B01", "name": "Bàn 1" },
  "groups": [
    { "id": "11111111-1111-4111-8111-111111111111", "name": "Món chính", "displayOrder": 0 }
  ]
}
```

Only active, undeleted groups of this restaurant are returned. Empty groups are allowed. Sort: `displayOrder ASC, name ASC, id ASC`. An empty menu has `groups: []`.

## GET /api/v1/public/menu/tables/{qrToken}/items

Optional query parameters:

| Parameter | Default | Validation / behavior |
| --- | --- | --- |
| `q` | absent | At most 100 characters; trimmed, case-insensitive item-name search. `%`, `_`, `\` are literal characters. Does not search SKU or private metadata. |
| `groupId` | absent | UUID of a visible group of this restaurant. Foreign, missing or hidden group returns 404. |
| `page` | `0` | Integer >= 0. |
| `size` | `20` | Integer 1–100. |
| `sortBy` | `name` | `name` or `salePrice` only. |
| `direction` | `asc` | `asc` or `desc` (case-insensitive). |

Sorting always adds `id ASC` as a stable tie-breaker. Filters are applied in SQL **before** pagination and counting. Groups for the returned items are loaded in one batch, not one query per item.

Example success (200):

```json
{
  "content": [
    {
      "id": "22222222-2222-4222-8222-222222222222",
      "group": { "id": "11111111-1111-4111-8111-111111111111", "name": "Món chính" },
      "name": "Phở bò",
      "description": "Phở bò tái",
      "imageUrl": null,
      "baseUnit": "tô",
      "salePrice": 50000.00,
      "availabilityStatus": "AVAILABLE"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`group` is null for ungrouped items. `description` and `imageUrl` can be null. An empty result is `content: []`, `totalElements: 0`, `totalPages: 0`; a page beyond the end is also an empty page, not 404.

Visibility requires MENU_ITEM, active, undeleted, and AVAILABLE or OUT_OF_STOCK. OUT_OF_STOCK is displayed so the website can label it unavailable. INGREDIENT and inactive/deleted items are hidden. An item's non-null group must also be active, undeleted and in this restaurant. The current model has no DISABLED availability enum: disabled items are represented by `active=false`.

Public responses never include `costPrice`, SKU, inventory tracking, recipes, metadata, feature snapshots, tenant/table UUIDs, QR tokens, staff, versions, timestamps or session details. Group/item IDs are public identifiers, not authorization for internal CRUD APIs.

## Errors

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Invalid query parameter or paging/sort option. |
| 403 | `QR_MENU_UNAVAILABLE` | No effective subscription or no QR_MENU_VIEW snapshot grant. |
| 404 | `QR_MENU_NOT_FOUND` | Token invalid/unknown/rotated, table unavailable, area unavailable, restaurant unavailable. |
| 404 | `PUBLIC_MENU_GROUP_NOT_FOUND` | Requested group does not exist or is not visible in the resolved restaurant. |
| 500 | `INTERNAL_ERROR` | Unexpected backend failure; not disguised as subscription denial. |

```json
{
  "success": false,
  "code": "QR_MENU_NOT_FOUND",
  "message": "Menu QR không còn khả dụng.",
  "fieldErrors": {},
  "timestamp": "2026-10-07T00:00:00Z"
}
```

Messages are human-readable; use HTTP status and `code` for website behavior. Errors do not echo the URL, token, rejected query values, SQL or exception details.

The existing table QR error contract uses `fieldErrors`; the new restaurant-menu controller contract uses `details`. This documentation correction does not change table QR response JSON.

## Postman smoke test

Set environment variables `baseUrl` (for example `http://localhost:8080`) and `qrToken` obtained from the existing authorized TABLE API. Select **No Auth**, including disabling inherited collection auth.

```text
GET {{baseUrl}}/api/v1/public/menu/tables/{{qrToken}}
GET {{baseUrl}}/api/v1/public/menu/tables/{{qrToken}}/items?page=0&size=20&sortBy=salePrice&direction=asc
GET {{baseUrl}}/api/v1/public/menu/tables/{{qrToken}}/items?q=phở
```

Test with a QR_MENU_VIEW-only snapshot, an empty menu, out-of-stock items, disabled groups, another restaurant's group ID, expired subscription and rotated token. Verify that `costPrice` is absent and Cache-Control includes no-store. Do not paste real production QR tokens into shared examples.

## Operations and security boundaries

- QR links are shareable bearer-like public links, not proof that a customer is physically at a table. No ordering/payment capability is implied.
- Rotation invalidates the old token for subsequent requests. Backend cannot retract content already downloaded or displayed in a browser.
- Application defaults keep Spring Web/Security request debugging off and Hibernate JDBC bind/extract tracing off to avoid recording tokens. Preserve these settings in environment overrides. Do not enable request/response tracing on these routes.
- Reverse proxy, load balancer, CDN, WAF, access logs and APM must mask the token segment in `/api/v1/public/menu/tables/{redacted}` (and `/items`) or disable URL logging for these routes. Redact Referer values too; use `Referrer-Policy: no-referrer` on the customer website. Never use tokens as metric labels.
- Apply edge rate limits to public endpoints and bound timeouts. This change adds paging limits but does not introduce a distributed rate-limiting platform.
- No new migration is needed: the existing table migration already enforces unique `qr_token`. No existing migration is edited.
- Not included: frontend, QR image generation, customer login, carts, ordering, session mutation, payment, recipes, subscription billing or caching.

## Tests

`PublicQrMenuPolicyTest` covers route boundaries, access-denial mapping, visibility policy and search validation. `PublicQrMenuIntegrationTest` uses a randomly named `it_public_qr_*` PostgreSQL schema, migrates from empty and drops only that schema after the suite. It checks public DTO allowlists, tenant isolation, filters/pagination, no-store, invalid links, subscription boundaries, security, query counts and no read-side mutations. `PublicQrMenuOpenApiTest` validates the documented routes and response field allowlists.
