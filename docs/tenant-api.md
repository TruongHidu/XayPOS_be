# Tenant profile and staff API

V32: [Snapshot công thức order item](order-recipe-snapshot-api.md) được chốt khi
SUBMIT/confirm/approve QR, không giữ chỗ/trừ kho. API đọc riêng
`GET /api/v1/orders/{orderId}/items/{orderItemId}/recipe-snapshot` cần ORDER_VIEW +
RECIPE_VIEW và ORDER_MANAGEMENT + RECIPE_MANAGEMENT; order/public QR không lộ recipe.

Đơn hàng nhân viên: [ORDER MVP API](order-api.md), namespace `/api/v1/orders`, permission ORDER_* và feature ORDER_MANAGEMENT. Ghi nhận thanh toán thủ công: [PAYMENT MVP API](payment-api.md), permission PAYMENT_* và feature PAYMENT_MANAGEMENT.

Lấy/khởi tạo/đổi link menu chung nhà hàng: [Public restaurant menu API](public-restaurant-menu-api.md), nhóm `/api/v1/restaurants/me/menu-link` yêu cầu `RESTAURANT_PROFILE_UPDATE`.

Customer-facing anonymous QR menu endpoints are documented separately in [Public QR menu API](public-qr-menu-api.md). Their anonymous access does not apply to any authenticated tenant endpoint below.

QR ordering uses [pending requests and staff approval](public-qr-order-api.md), not anonymous access to ORDER/TABLE/PAYMENT. Guest submission requires no session/PIN; actual TABLE session opening happens only during authorized staff approval. QR takeaway does not require TABLE features/permissions.

All endpoints require `Authorization: Bearer <accessToken>`. The tenant comes exclusively from the authenticated principal. Request bodies must not contain `restaurantId`. Unknown properties are rejected. System accounts without a tenant cannot use these APIs. Responses never contain passwords, token material, `deletedAt`, raw restaurant settings, or `publicOrderToken`.

## Authorization

| Endpoint | Permission | Features |
|---|---|---|
| GET /api/v1/restaurants/me | RESTAURANT_PROFILE_VIEW | None |
| PATCH /api/v1/restaurants/me | RESTAURANT_PROFILE_UPDATE | None |
| GET /api/v1/staff | STAFF_VIEW | STAFF_MANAGEMENT |
| GET /api/v1/staff/roles | STAFF_VIEW | STAFF_MANAGEMENT |
| GET /api/v1/staff/{staffId} | STAFF_VIEW | STAFF_MANAGEMENT |
| POST /api/v1/staff | STAFF_CREATE | STAFF_MANAGEMENT |
| PATCH /api/v1/staff/{staffId} | STAFF_UPDATE | STAFF_MANAGEMENT |
| PATCH /api/v1/staff/{staffId}/status | STAFF_DISABLE | STAFF_MANAGEMENT |
| GET /api/v1/staff/permissions | STAFF_PERMISSION_MANAGE | STAFF_MANAGEMENT, STAFF_PERMISSION |
| GET /api/v1/staff/{staffId}/permissions | STAFF_PERMISSION_MANAGE | STAFF_MANAGEMENT, STAFF_PERMISSION |
| PUT /api/v1/staff/{staffId}/permissions | STAFF_PERMISSION_MANAGE | STAFF_MANAGEMENT, STAFF_PERMISSION |

The restaurant must still be active. Profile access works before package assignment and after subscription expiry. Staff endpoints check the immutable subscription snapshot, not live package definitions. Missing effective subscription returns `SUBSCRIPTION_NOT_ACTIVE`; a missing feature returns `FEATURE_NOT_ENTITLED` (both 403).

OWNER manages MANAGER, WAITER, KITCHEN and CASHIER. MANAGER only manages WAITER, KITCHEN and CASHIER. Other roles cannot manage staff even if granted a STAFF permission. OWNER is visible in the directory but protected from all staff mutations. Self-mutation, assigning OWNER/SUPER_ADMIN, custom roles and hard deletion are unsupported. MANAGER receives basic staff permissions in V10 but not STAFF_PERMISSION_MANAGE.

## Restaurant profile

`GET /api/v1/restaurants/me` returns 200:

```json
{"id":"11111111-1111-4111-8111-111111111111","code":"DEMO","name":"Demo Restaurant","legalName":null,"phone":null,"address":null,"timezone":"Asia/Ho_Chi_Minh","currencyCode":"VND","status":"ACTIVE","createdAt":"2026-09-22T00:00:00Z","updatedAt":"2026-09-22T00:00:00Z"}
```

`PATCH /api/v1/restaurants/me` request:

```json
{"name":"Demo Updated","legalName":"Demo Company","phone":"0900000000","address":"New address","timezone":"Asia/Ho_Chi_Minh","currencyCode":"vnd"}
```

Returns 200 with the same profile shape. Omitted/null fields remain unchanged. Empty optional `legalName`, `phone` and `address` clear the value to null. Name/timezone/currency cannot be blank. Currency is normalized uppercase and validated with Currency; timezone is validated with ZoneId. An empty update returns 400 `EMPTY_UPDATE_REQUEST`; invalid values return 400 `INVALID_TIMEZONE`, `INVALID_CURRENCY_CODE` or `VALIDATION_ERROR`. Read-only fields/unknown properties return 400 `INVALID_REQUEST_BODY`.

## Staff directory and roles

`GET /api/v1/staff?q=alice&roleCode=WAITER&active=true&page=0&size=20&sortBy=createdAt&direction=desc` returns 200:

```json
{"content":[{"id":"22222222-2222-4222-8222-222222222222","name":"Alice","email":"alice@example.com","phone":null,"active":true,"role":{"id":"33333333-3333-4333-8333-333333333333","code":"WAITER","name":"Waiter"},"lastLoginAt":null,"createdAt":"2026-09-22T00:00:00Z","updatedAt":"2026-09-22T00:00:00Z"}],"page":0,"size":20,"totalElements":1,"totalPages":1}
```

Filters are optional; q matches name/email/phone with literal wildcard characters. Page starts at zero; size is 1–100. Sort is restricted to `createdAt`, `name`, `email`, `lastLoginAt`; id breaks ties. Invalid filters/sort/page return 400 `VALIDATION_ERROR`.

`GET /api/v1/staff/{staffId}` returns the single staff object above. Missing, deleted and foreign-tenant targets all return 404 `USER_NOT_FOUND`.

`GET /api/v1/staff/roles` returns active system roles assignable by the actor, ordered by code:

```json
[{"id":"33333333-3333-4333-8333-333333333333","code":"WAITER","name":"Waiter"}]
```

## Create, update and status

`POST /api/v1/staff` request:

```json
{"name":"Alice","email":"ALICE@example.com","phone":"0900000000","roleCode":"WAITER","initialPassword":"ExamplePassword123!"}
```

Returns 201 with the staff object shown above. Email is trimmed and lowercased; roleCode is trimmed and uppercased. Initial password is 8–100 characters, supplied over HTTPS, hashed and never returned. Email is globally unique, including retained deleted rows. Duplicate email returns 409 `EMAIL_EXISTS`. Forbidden role assignment returns 400 `STAFF_ROLE_NOT_ALLOWED`; unavailable/inactive role returns 404 `ROLE_NOT_FOUND`.

`PATCH /api/v1/staff/{staffId}` request:

```json
{"name":"Alice Updated","email":"alice-new@example.com","phone":"","roleCode":"KITCHEN"}
```

Returns 200 with updated staff. Omitted/null fields are unchanged; blank phone clears it. Empty update returns `EMPTY_UPDATE_REQUEST`. Blank name/email/roleCode is invalid. A real role change clears all old permission overrides in the same transaction. Email or role changes revoke all refresh tokens; cosmetic changes do not. Protected/self/unauthorized targets return 403 `OWNER_ACCOUNT_PROTECTED`, `STAFF_SELF_MANAGEMENT_NOT_ALLOWED` or `STAFF_TARGET_FORBIDDEN`.

`PATCH /api/v1/staff/{staffId}/status` request:

```json
{"active":false,"reason":"Employee left"}
```

Returns 200 with updated staff. `active` is required; reason is required for disable and has max 500 characters. Reactivate with `{"active":true}`. STAFF_DISABLE controls both directions. Repeating the same valid status is idempotent and does not create another audit or revoke sessions again. Disabling revokes refresh tokens; enabling does not issue tokens. No staff row is deleted.

## Permission catalog and overrides

`GET /api/v1/staff/permissions` returns 200:

```json
[{"code":"ORDER_CANCEL","module":"ORDER","name":"Cancel order","description":null}]
```

Only active permissions in MENU, TABLE, ORDER, KITCHEN, PAYMENT, STAFF, INVENTORY, REPORT and AI are assignable. ADMIN, AUDIT, SUBSCRIPTION and RESTAURANT_PROFILE permissions are excluded. This catalog is not a promise the actor may grant every entry: grants must also be a subset of the actor's current DB effective permissions.

`GET /api/v1/staff/{staffId}/permissions` returns 200:

```json
{"staffId":"22222222-2222-4222-8222-222222222222","roleCode":"WAITER","rolePermissions":["MENU_VIEW","ORDER_VIEW"],"grants":["ORDER_CANCEL"],"denies":["MENU_VIEW"],"effectivePermissions":["ORDER_CANCEL","ORDER_VIEW"]}
```

Lists are sorted. Permission examples are abbreviated; actual role defaults are returned. Effective permissions use the existing rule `(role permissions UNION grants) MINUS denies`.

`PUT /api/v1/staff/{staffId}/permissions` request:

```json
{"grants":["ORDER_CANCEL"],"denies":["TABLE_CLOSE"]}
```

Returns 200 with the permission response above. This replaces all overrides atomically. Both arrays are required and may be empty; two empty arrays clear all overrides. Codes are trimmed, uppercased and deduplicated. Overlap returns 400 `PERMISSION_EFFECT_CONFLICT`; unknown/inactive permission returns 400 `PERMISSION_NOT_FOUND`; system permission returns 400 `PERMISSION_NOT_ASSIGNABLE`; granting an authority the actor does not possess returns 403 `PERMISSION_ESCALATION_NOT_ALLOWED`. Same assignment is a no-op. A changed assignment revokes refresh tokens.

## Capacity, transactions and audit

`maxStaff` is read from the active subscription snapshot, never the live package. Version 2 stores it at snapshot root; explicit null means unlimited. Legacy version 1 reads `STAFF_MANAGEMENT.limits.maxStaff`, with absent key meaning unlimited. Present non-null limits must be numeric positive integers; malformed configuration fails with 409 `INVALID_STAFF_LIMIT_CONFIG`. STAFF_MANAGEMENT is still required independently of maxStaff. Count includes active, non-deleted MANAGER/WAITER/KITCHEN/CASHIER and excludes OWNER. Create/reactivate at capacity returns 409 `STAFF_LIMIT_REACHED`; disable frees a seat. Restaurant-first then user locking serializes capacity changes. Other write conflicts return 409 `CONCURRENT_STAFF_UPDATE`.

Successful mutations and audits commit together. Action codes are RESTAURANT_PROFILE_UPDATED, STAFF_CREATED, STAFF_UPDATED, STAFF_ROLE_CHANGED, STAFF_DISABLED, STAFF_ENABLED and STAFF_PERMISSIONS_UPDATED. No-op/rejected requests produce no success audit. A request changing both staff profile and role records the two corresponding events. Secrets are never included.

V11 adds STAFF_MANAGEMENT and TABLE_MANAGEMENT to the standard BASIC catalog and QR_MENU_VIEW to
all three standard plans. Empty STAFF_MANAGEMENT limits in BASIC/PRO/PREMIUM become maxStaff 3/10/30;
nonempty custom limits are preserved. V12 moves maxStaff into the package column. Admin configures
the top-level maxStaff using package POST/PUT (see admin-api.md), not feature limits.
These catalog changes do not grant features or limits to already
activated snapshots. A newly activated BASIC subscription can create CASHIER accounts within its
limit. Lowering a catalog limit never disables existing staff; create/reactivate checks the active
snapshot's remaining capacity. QR_MENU_VIEW is catalog metadata; no public menu endpoint is added.

V13 gives newly activated BASIC subscriptions STAFF_PERMISSION, KITCHEN_DISPLAY, DETAIL_REPORT,
KITCHEN_TICKET_PRINT and KITCHEN_TICKET_REPRINT. PRO includes the same standard operational features
plus RECIPE_MANAGEMENT; default maxStaff remains BASIC=3 and PRO=10. PREMIUM is unchanged.
Existing active snapshots do not receive these additions automatically. Pending subscriptions
capture the current catalog at activation. Permission/role checks still apply independently of
package features. This catalog migration does not implement recipe or inventory endpoints.

## Error and session contract

All endpoints use the existing error envelope, for example:

```json
{"success":false,"code":"USER_NOT_FOUND","message":"User not found","fieldErrors":{},"timestamp":"2026-09-22T00:00:00Z"}
```

Missing/invalid authentication returns 401 `UNAUTHORIZED`. Missing permission returns 403 `FORBIDDEN`; missing tenant returns 403 `TENANT_ACCESS_DENIED`. A disabled/deleted bearer account returns 401 `ACCOUNT_INACTIVE` on the next protected request. Restaurant status checks still apply. Error precedence may report authorization or entitlement before target lookup.

Access JWTs still contain role/permission snapshots. Refresh revocation does not invalidate an already issued access JWT. Role/permission changes therefore propagate after re-login or by expiry (default access TTL 15 minutes). Account disable is checked on every protected bearer request. An old unexpired access JWT can become usable again if the account is re-enabled; refresh tokens stay revoked and new sessions require login. No access-token blacklist or security version is implemented.

V10 adds tenant profile permissions to OWNER/MANAGER and basic staff permissions to MANAGER. Existing JWTs need refresh/login to receive new authorities. Admin restaurant-user endpoints remain system-admin read-only and retain their existing contract.

Invitations, password reset/change, ownership transfer, custom role CRUD, scheduling, POS operations and package payments are outside this phase.

## Menu groups and items (V14)

Every menu API requires a Bearer JWT, a tenant context, an active account/restaurant,
an effective subscription with `MENU_MANAGEMENT`, and the permission below.
No restaurantId is accepted in JSON; the tenant always comes from the principal.
MANAGER has no default MENU grants: OWNER may grant permissions through staff
overrides. Access is permission-based, not restricted to OWNER/MANAGER roles.

| Method and path | Permission | Success |
|---|---|---|
| GET /api/v1/menu/groups | MENU_VIEW | 200 page |
| GET /api/v1/menu/groups/{groupId} | MENU_VIEW | 200 group |
| POST /api/v1/menu/groups | MENU_CREATE | 201 group |
| PATCH /api/v1/menu/groups/{groupId} | MENU_UPDATE | 200 group |
| PATCH /api/v1/menu/groups/{groupId}/status | MENU_UPDATE | 200 group |
| DELETE /api/v1/menu/groups/{groupId}?expectedVersion=0 | MENU_DELETE | 204 |
| GET /api/v1/menu/items | MENU_VIEW | 200 page |
| GET /api/v1/menu/items/{itemId} | MENU_VIEW | 200 item |
| POST /api/v1/menu/items | MENU_CREATE | 201 item |
| PATCH /api/v1/menu/items/{itemId} | MENU_UPDATE | 200 item |
| PATCH /api/v1/menu/items/{itemId}/status | MENU_UPDATE | 200 item |
| PATCH /api/v1/menu/items/{itemId}/availability | MENU_UPDATE | 200 item |
| PUT /api/v1/menu/items/{itemId}/group | MENU_UPDATE | 200 item |
| DELETE /api/v1/menu/items/{itemId}?expectedVersion=0 | MENU_DELETE | 204 |

Headers: `Authorization: Bearer <accessToken>`, `Content-Type: application/json`
for JSON bodies. POST examples:

```json
{"name":"Drinks","displayOrder":0,"active":true}
```

```json
{"groupId":null,"sku":"coffee01","name":"Coffee","baseUnit":"cup","description":"Iced coffee","imageUrl":"https://example.com/coffee.jpg","salePrice":35000,"active":true}
```

Group create requires name (trimmed, 1–100 chars); displayOrder defaults 0
(nonnegative) and active defaults true. Group names are case-insensitively unique
among nondeleted groups in each tenant.
Item create requires name (1–150), baseUnit (1–30), salePrice (0–999999999999.99,
at most 2 decimal places). Optional sku max80, description max10000, imageUrl
max500, groupId and active. Empty optional text is normalized to null.
SKU is uppercase, unique in the tenant across both item types, and remains
reserved after soft deletion. imageUrl must be absolute HTTP(S), without
credentials; the server never fetches it.

Example group response:
```json
{"id":"11111111-1111-4111-8111-111111111111","name":"Drinks","displayOrder":0,"active":true,"version":0,"createdAt":"2026-10-02T00:00:00Z","updatedAt":"2026-10-02T00:00:00Z"}
```

Example item response (`costPrice` is available to authenticated tenant menu management;
inventory flags and metadata remain excluded):
```json
{"id":"22222222-2222-4222-8222-222222222222","group":null,"sku":"COFFEE01","name":"Coffee","baseUnit":"cup","description":"Iced coffee","imageUrl":"https://example.com/coffee.jpg","salePrice":35000.00,"costPrice":12000.00,"active":true,"availabilityStatus":"AVAILABLE","sellable":true,"version":0,"createdAt":"2026-10-02T00:00:00Z","updatedAt":"2026-10-02T00:00:00Z"}
```

When assigned, group is `{"id":"<uuid>","name":"Drinks","active":true}`.
List responses are `{"content":[...],"page":0,"size":20,"totalElements":1,"totalPages":1}`.
Both lists accept q (max100), active, page>=0, size1–100 (default20),
sortBy (default createdAt), direction asc/desc (default desc).
Group sort: createdAt/name/displayOrder/updatedAt. Item sort:
createdAt/name/salePrice/updatedAt. IDs break ties. Search matches literal
wildcards; item search covers name and SKU. Item-only filters: groupId,
availabilityStatus, sellable. Item group data is batch loaded, not N+1.

Khi menu item có công thức active, đổi `baseUnit` trả `MENU_ITEM_UNIT_IN_USE` và
xóa mềm trả `MENU_ITEM_HAS_ACTIVE_RECIPE`. Tắt/bật món, đổi tên, giá, mô tả, ảnh
hoặc group vẫn được phép.

PATCH group accepts name/displayOrder/expectedVersion. PATCH item accepts
name/sku/baseUnit/description/imageUrl/salePrice/costPrice/expectedVersion.
Omitted/null fields stay unchanged; blank sku/description/imageUrl clears them.
Blank name/baseUnit is invalid. A body with only expectedVersion returns
EMPTY_UPDATE_REQUEST. Lifecycle/internal/type/tenant fields are not accepted.
Examples:

```json
{"name":"New group name","displayOrder":1,"expectedVersion":0}
```

```json
{"name":"New coffee name","salePrice":40000,"expectedVersion":0}
```

```json
{"active":false,"expectedVersion":0}
```

```json
{"availabilityStatus":"OUT_OF_STOCK","expectedVersion":0}
```

```json
{"groupId":"11111111-1111-4111-8111-111111111111","expectedVersion":0}
```

```json
{"groupId":null,"expectedVersion":0}
```

## Recipe versions (V29)

Recipe API nằm dưới `/api/v1/menu/items/{menuItemId}/recipes`, yêu cầu feature
`RECIPE_MANAGEMENT` và permission `RECIPE_VIEW/CREATE/UPDATE/DELETE` tương ứng.
Version active là bất biến: sửa công thức tạo business version mới và giữ history;
deactivate là soft transition, không hard delete. Ingredient và yield unit được
snapshot để lịch sử không đổi. Chi tiết header, body, response, lỗi và Postman xem
[Recipe API](recipe-api.md). Contract đầy đủ nằm trong
[tenant OpenAPI](openapi/tenant-api.yaml).

`GET /api/v1/menu/items/{menuItemId}/recipes/{recipeId}/cost-estimate` yêu cầu
`RECIPE_VIEW` và `RECIPE_MANAGEMENT`, không cần kho/tồn hoặc permission inventory.
GET không body, không expectedVersion/idempotency key; trả `Cache-Control: no-store`.
Định lượng/tên/SKU/đơn vị/yield lấy từ snapshot phiên bản được chọn (active hoặc
inactive), đơn giá từ `ingredient.costPrice` hiện tại trên một baseUnit. Mọi
nguyên liệu hợp lệ đều được tính, kể cả không tracking/inactive/soft-deleted;
snapshot unit khác current baseUnit trả `409 INGREDIENT_COST_UNIT_MISMATCH`.
Gross quantity giữ chính xác; lineCost HALF_UP 2 chữ số, tổng cộng các line đã làm
tròn, per-yield HALF_UP 2 chữ số. Giá 0 hợp lệ và có warning
`ZERO_INGREDIENT_REFERENCE_COST`; thiếu giá không tự thay bằng 0. Response có
`costSource=INGREDIENT_COST_PRICE`, `totalBatchCost`, `costPerYieldUnit`, ingredient
lines, warnings và `estimatedAt` từ Clock. Đây là ước tính theo giá hiện tại,
không cập nhật costPrice/menu/recipe/tồn/ledger/audit và không phải giá vốn lịch sử.
Xem ví dụ Postman và hướng dẫn Flutter/web trong [Recipe API](recipe-api.md).

The last two bodies belong to PUT /items/{id}/group. groupId must be present;
explicit null removes the assignment. New assignments require an active,
nondeleted same-tenant group. An unchanged assignment is a no-op even if the
existing group has since been disabled.

Lifecycle and visibility:

- Create initializes MENU_ITEM, AVAILABLE, active=true unless explicitly false.
- Active items may switch AVAILABLE/OUT_OF_STOCK. Inactive items cannot change
  availability (409 INVALID_MENU_ITEM_TRANSITION).
- Disable/enable preserves availability. Re-enabling an out-of-stock item does
  not make it available.
- Disabling a group changes item sellability without changing child state/version.
- sellable requires a nondeleted active AVAILABLE item and, if grouped, an active
  nondeleted group. It is computed; inventory is not consulted.
- Delete is soft. Groups with any nondeleted items, including inactive items,
  cannot be deleted. Deleted/foreign-tenant/INGREDIENT IDs are hidden from menu
  GET/PATCH. Repeated same-tenant DELETE returns 204.
- PATCH/PUT requires nonnegative expectedVersion; DELETE requires that query
  parameter. Actual changes with a stale version return CONCURRENT_MENU_UPDATE.
  State/assignment/delete no-ops succeed even with an old version and do not
  change version/updatedAt or create audit. Profile edits still require a current
  version, including profile requests whose values already match.

Errors use the existing error envelope:

- 400 VALIDATION_ERROR, INVALID_REQUEST_BODY, EMPTY_UPDATE_REQUEST.
- 401 UNAUTHORIZED/ACCOUNT_INACTIVE; existing restaurant/account filters apply.
- 403 FORBIDDEN, TENANT_ACCESS_DENIED, SUBSCRIPTION_NOT_ACTIVE,
  FEATURE_NOT_ENTITLED (and restaurant status errors).
- 404 MENU_GROUP_NOT_FOUND, MENU_ITEM_NOT_FOUND.
- 409 MENU_GROUP_NAME_EXISTS, SKU_EXISTS, MENU_GROUP_INACTIVE,
  MENU_GROUP_NOT_EMPTY, INVALID_MENU_ITEM_TRANSITION, CONCURRENT_MENU_UPDATE.

Menu writes lock the tenant restaurant before loading menu resources and execute
with audit in one transaction; this serializes low-frequency configuration writes
inside one tenant and closes the group-delete/create race. Reads do not acquire
that lock. @Version plus expectedVersion prevents stale forms from overwriting
changes. Named database constraints remain authoritative. Future higher write
volume can replace this with coordinated group/item locking.

Audit actions: MENU_GROUP_CREATED/UPDATED/ENABLED/DISABLED/DELETED;
MENU_ITEM_CREATED/UPDATED/GROUP_CHANGED/ENABLED/DISABLED/AVAILABILITY_CHANGED/DELETED.
Rejected/no-op requests do not record success. Audit failure rolls back mutation.

Creation uses command service → MenuItemFactory → ItemCreationStrategyRegistry
→ MenuItemCreationStrategy; persistence/audit stay in the command service.
The stateless strategy receives trusted normalized input and a Clock timestamp.
Registry rejects duplicate types and requires both MENU_ITEM and INGREDIENT support.
V28 supplies `IngredientCreationStrategy` and the separately authorized ingredient API.
A recipe is a versioned relation of MENU_ITEM to INGREDIENT, not another item type
or strategy for a package/role. Add a recipe application boundary when implementing
that feature; do not put recipe payloads in metadata. This release has no recipe,
inventory, public QR, variants or upload endpoints.

## Ingredient catalog (V28)

Ingredient CRUD uses `/api/v1/menu/ingredients`, persists `items.item_type=INGREDIENT`
and never broadens `/api/v1/menu/items`. It requires the matching
`INGREDIENT_VIEW/CREATE/UPDATE/DELETE` permission plus at least one of
`RECIPE_MANAGEMENT` or `INVENTORY_MANAGEMENT` in the effective immutable snapshot.
Tenant comes only from JWT. OWNER and system SUPER_ADMIN receive these permissions
by default; MANAGER/WAITER/CASHIER/KITCHEN do not.

| Method and path | Permission | Success |
|---|---|---|
| GET /api/v1/menu/ingredients | INGREDIENT_VIEW | 200 page |
| GET /api/v1/menu/ingredients/{ingredientId} | INGREDIENT_VIEW | 200 ingredient |
| POST /api/v1/menu/ingredients | INGREDIENT_CREATE | 201 ingredient |
| PATCH /api/v1/menu/ingredients/{ingredientId} | INGREDIENT_UPDATE | 200 ingredient |
| PATCH /api/v1/menu/ingredients/{ingredientId}/status | INGREDIENT_UPDATE | 200 ingredient |
| DELETE /api/v1/menu/ingredients/{ingredientId}?expectedVersion=0 | INGREDIENT_DELETE | 204 |

Search/filter/sort, request/response examples, Postman headers, version semantics,
errors and current exclusions are documented in [Ingredient catalog API](ingredient-api.md).
`costPrice` is the current reference cost per base unit; `trackInventory` alone
does not create stock. Confirming a RECEIPT now updates tracked ingredient costPrice
using moving weighted average over all tenant warehouses. Drafts, ISSUE and
ADJUSTMENT do not reprice; confirmed replay does not recalculate. Price/stock/ledger
and audit commit atomically. Refresh ingredient detail/version and recipe estimate
after receipt confirmation; sale prices and menu-item costPrice are unchanged.
This is not a complete historical inventory valuation system. See inventory-api.md.
Ingredient has no group/sale availability and cannot be sold or exposed publicly.
## Tables, areas and seating sessions (V15)

The explicitly authorized QR management endpoints below are the exception to the
ordinary tenant responses' no-token rule; they return the QR payload for printing.

All 21 operations require an active bearer account/restaurant, a tenant from JWT,
an effective subscription, and `TABLE_MANAGEMENT` in its immutable snapshot.
Package names do not authorize access. System SUPER_ADMIN without tenant context
cannot call these APIs. There are no role defaults or feature snapshot changes in V15.
CASHIER/MANAGER need the relevant effective permission grants.
V24 adds TABLE_CLOSE to global system CASHIER/MANAGER for atomic DINE_IN order
completion and session closure. This permission also applies to the manual close
and cancel endpoints, which retain their existing business guards. User DENY and
snapshot TABLE_MANAGEMENT still apply; login/refresh is required for new grants.

V25 table close/cancel/transfer/QR rotation additionally invalidates affected table guest access epochs/grants and cancels PENDING TABLE ordering requests in the same transaction. Opening the first session does not invalidate newly issued pre-seating grants. RESTAURANT takeaway grants/requests and APPROVED order/payment history are unaffected. Existing TABLE bodies/responses and closure/completion conditions are unchanged; [scope details](public-qr-order-api.md#scope-checkoutlifecycle).

### Headers and endpoint contracts

```http
Authorization: Bearer <accessToken>
Content-Type: application/json
```

Content-Type is required for JSON request bodies. Do not send restaurantId or a
tenant override header. The base path is `/api/v1`.

| Method and path | Permission | Body/query | Success response |
| --- | --- | --- | --- |
| GET /table-areas | TABLE_VIEW | Area search below | 200 Page<Area> |
| POST /table-areas | TABLE_CREATE | CreateArea | 201 Area |
| GET /table-areas/{areaId} | TABLE_VIEW | — | 200 Area |
| PATCH /table-areas/{areaId} | TABLE_UPDATE | UpdateArea | 200 Area |
| PATCH /table-areas/{areaId}/status | TABLE_UPDATE | AreaStatus | 200 Area |
| DELETE /table-areas/{areaId} | TABLE_UPDATE | expectedVersion query, required | 204 |
| GET /tables | TABLE_VIEW | Table search below | 200 Page<Table> |
| POST /tables | TABLE_CREATE | CreateTable | 201 Table |
| GET /tables/{tableId} | TABLE_VIEW | — | 200 Table |
| PATCH /tables/{tableId} | TABLE_UPDATE | UpdateTable | 200 Table |
| PATCH /tables/{tableId}/status | TABLE_UPDATE | TableStatus | 200 Table |
| PATCH /tables/{tableId}/area | TABLE_UPDATE | AreaAssignment | 200 Table |
| DELETE /tables/{tableId} | TABLE_UPDATE | expectedVersion query, required | 204 |
| GET /tables/{tableId}/qr | TABLE_UPDATE | — | 200 QR |
| POST /tables/{tableId}/qr/rotate | TABLE_UPDATE | Version | 200 QR |
| POST /tables/{tableId}/sessions | TABLE_OPEN | OpenSession | 201 Session |
| GET /tables/{tableId}/current-session | TABLE_VIEW | — | 200 Session; 204 when no OPEN |
| GET /table-sessions | TABLE_VIEW | Session search below | 200 Page<Session> |
| GET /table-sessions/{sessionId} | TABLE_VIEW | — | 200 Session |
| PATCH /table-sessions/{sessionId} | TABLE_OPEN | UpdateSession | 200 Session |
| POST /table-sessions/{sessionId}/cancel | TABLE_CLOSE | CancelSession | 200 Session |

No TABLE_DELETE/TABLE_CANCEL permission is introduced. QR management is deliberately
restricted to TABLE_UPDATE rather than TABLE_VIEW.

### Request body examples

CreateArea (name required; active defaults true, displayOrder defaults 0):

```json
{"name":"Tầng 1","description":"Khu trong nhà","displayOrder":0,"active":true}
```

UpdateArea; AreaStatus:

```json
{"name":"Tầng trệt","description":"","displayOrder":1,"expectedVersion":0}
```

```json
{"active":false,"expectedVersion":1}
```

CreateTable (code/name required; areaId nullable; capacity 4,
displayOrder 0 and status AVAILABLE by default):

```json
{"areaId":null,"code":"B01","name":"Bàn 01","capacity":4,"displayOrder":0,"status":"AVAILABLE"}
```

UpdateTable; TableStatus:

```json
{"name":"Bàn cửa sổ","capacity":6,"displayOrder":2,"expectedVersion":0}
```

```json
{"status":"INACTIVE","expectedVersion":1}
```

AreaAssignment (both properties required); Version for QR rotation:

```json
{"areaId":null,"expectedVersion":2}
```

```json
{"expectedVersion":3}
```

Replace null areaId with a same-tenant active area's UUID to assign it.
Omitting areaId is INVALID_REQUEST_BODY; explicit null unassigns.

OpenSession (expectedVersion is the TABLE version; guestCount defaults 1):

```json
{"guestCount":3,"note":"Khách ngồi chờ","expectedVersion":0}
```

UpdateSession and CancelSession (expectedVersion is the SESSION version):

```json
{"guestCount":2,"note":"","expectedVersion":0}
```

```json
{"reason":"Mở nhầm bàn","expectedVersion":1}
```

These are independent examples: use the version returned by the most recent
response for the resource being changed, rather than copying the example number.

Validation:
- Names trim to nonblank text, max 100 characters; table code trims and uppercases
  with Locale.ROOT, max 50. Area names are unique case-insensitively within tenant.
- capacity and guestCount are integers in 1..32767. Capacity is advisory:
  guestCount may exceed capacity. displayOrder is nonnegative.
- Area description and session note: max 2000 characters; cancellation reason:
  required nonblank max 1000.
- PATCH missing/null normally keeps a field; empty description/note clears it.
  A PATCH without any business field is EMPTY_UPDATE_REQUEST.
- Body properties such as restaurantId, qrToken, sessionCode, actor IDs,
  timestamps, occupancy, currentSession and arbitrary session status are rejected.
- expectedVersion is required and nonnegative on mutations other than creation
  of area/table. Version is checked before configuration no-ops.
- A valid no-op keeps version/updatedAt and emits no audit.
- Repeated delete of a soft-deleted resource returns 204. Repeated cancellation
  of an already CANCELLED session returns its original terminal data even when
  using the initial expectedVersion; no duplicate audit and no reason/time overwrite.

### Response examples

Area:

```json
{
  "id":"11111111-1111-4111-8111-111111111111",
  "name":"Tầng 1",
  "description":"Khu trong nhà",
  "displayOrder":0,
  "active":true,
  "version":0,
  "createdAt":"2026-10-06T03:00:00Z",
  "updatedAt":"2026-10-06T03:00:00Z"
}
```

Table with an OPEN session:

```json
{
  "id":"22222222-2222-4222-8222-222222222222",
  "code":"B01",
  "name":"Bàn 01",
  "capacity":4,
  "displayOrder":0,
  "status":"AVAILABLE",
  "area":{"id":"11111111-1111-4111-8111-111111111111","name":"Tầng 1","active":true},
  "occupied":true,
  "currentSession":{
    "id":"33333333-3333-4333-8333-333333333333",
    "sessionCode":"TS-1234567890abcdef1234567890abcdef",
    "guestCount":3,
    "openedAt":"2026-10-06T03:01:00Z",
    "version":0
  },
  "canOpen":false,
  "version":0,
  "createdAt":"2026-10-06T03:00:00Z",
  "updatedAt":"2026-10-06T03:00:00Z"
}
```

AVAILABLE/INACTIVE is table configuration. `occupied` comes exclusively from an
OPEN session. Opening/cancelling a session does not increment the table's
configuration version. `canOpen` is only a configuration/occupancy hint; FE
must also check permissions, and BE always revalidates under the mutation lock.

Session:

```json
{
  "id":"33333333-3333-4333-8333-333333333333",
  "sessionCode":"TS-1234567890abcdef1234567890abcdef",
  "tableId":"22222222-2222-4222-8222-222222222222",
  "tableCode":"B01",
  "tableName":"Bàn 01",
  "status":"CANCELLED",
  "guestCount":3,
  "note":"Khách ngồi chờ",
  "openedBy":"44444444-4444-4444-8444-444444444444",
  "openedAt":"2026-10-06T03:01:00Z",
  "closedBy":"44444444-4444-4444-8444-444444444444",
  "closedAt":"2026-10-06T03:02:00Z",
  "cancelReason":"Mở nhầm bàn",
  "version":1,
  "createdAt":"2026-10-06T03:01:00Z",
  "updatedAt":"2026-10-06T03:02:00Z"
}
```

closedAt/closedBy represent termination for CANCELLED and CLOSED
status. In OPEN they, together with cancelReason, are null. User IDs are returned,
not full User entities. Table code/name are current table metadata, not historical
snapshots. Session history remains accessible after table/area soft deletion.

QR (management endpoints only; token below is illustrative):

```json
{
  "tableId":"22222222-2222-4222-8222-222222222222",
  "qrToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "qrPath":"/menu/qr/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "version":1
}
```

Tokens use 32 SecureRandom bytes encoded base64url without padding. Rotation
changes the token and table version even during an OPEN session; the previous
token no longer matches the table in DB. Ordinary responses/audit never include
tokens, token hashes or token-bearing paths. GET QR is read-only and emits no
mutation audit. Public menu reads and QR ordering are now separate APIs documented above;
these TABLE management endpoints do not render QR images or let guests mutate sessions/orders.

The datasource sets pgJDBC logServerErrorDetail=false so constraint exceptions
do not echo QR values into application logs. Keep this setting in deployment
overrides; do not enable JDBC bind-value or HTTP response-body logging for QR data.

All list responses use:
```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

### Search, pagination and business rules

Common page=0, size=20 (max 100), direction=desc. Every sort adds id as a
tie-breaker. Invalid sortBy/direction is VALIDATION_ERROR.

- Areas: q (name, max 100), active; sortBy displayOrder (default),
  name, createdAt, updatedAt.
- Tables: q (code/name, max 100), areaId, status, occupied; sortBy
  displayOrder (default), name, code, capacity, createdAt, updatedAt.
- Sessions: tableId, status, openedFrom, openedTo (ISO-8601 instants,
  inclusive bounds, from <= to); sortBy openedAt (default),
  sessionCode, createdAt, updatedAt.

q matches literal case-insensitive text: %, _ and backslash are escaped.
occupied filtering runs in SQL before pagination. Areas/current sessions/table
metadata are batch-loaded; list endpoints do not query once per row.

Area cannot be disabled while it contains OPEN sessions. Disabling it preserves
individual table configuration, but prevents new openings. Area cannot be deleted
while it contains non-deleted tables, including inactive ones. Move/delete these
tables first.

Occupied tables cannot change code/capacity/area, be disabled or deleted.
Name/displayOrder may change. Bàn without area is valid. Assignments require an
active, non-deleted same-tenant area. Code remains reserved after soft deletion.

All mutations acquire the restaurant pessimistic write lock before reading state,
then update/audit in the same transaction. @Version and a partial unique index
(one OPEN session per table) provide additional protection. Auditing failure rolls
back the mutation. Rejected calls and no-ops emit no successful audit.

### Errors

Envelope is unchanged:

```json
{
  "success":false,
  "code":"TABLE_ALREADY_OCCUPIED",
  "message":"Table already has an open session",
  "fieldErrors":{},
  "timestamp":"2026-10-06T03:01:01Z"
}
```

| HTTP | Codes |
| --- | --- |
| 400 | VALIDATION_ERROR, INVALID_REQUEST_BODY, EMPTY_UPDATE_REQUEST |
| 401 | UNAUTHORIZED, ACCOUNT_INACTIVE |
| 403 | FORBIDDEN, TENANT_ACCESS_DENIED, RESTAURANT_INACTIVE, SUBSCRIPTION_NOT_ACTIVE, FEATURE_NOT_ENTITLED |
| 404 | TABLE_AREA_NOT_FOUND, TABLE_NOT_FOUND, TABLE_SESSION_NOT_FOUND |
| 409 | TABLE_AREA_NAME_EXISTS, TABLE_CODE_EXISTS, TABLE_AREA_INACTIVE, TABLE_AREA_NOT_EMPTY, TABLE_AREA_HAS_OPEN_SESSIONS, TABLE_INACTIVE, TABLE_ALREADY_OCCUPIED, TABLE_HAS_OPEN_SESSION, INVALID_TABLE_SESSION_TRANSITION, CONCURRENT_TABLE_UPDATE, QR_TOKEN_CONFLICT |

Foreign-tenant resource IDs are hidden as 404, including filtered resource lookups.
Stale version/lock conflicts are 409. DB constraint failures are translated without
raw SQL/details. Authentication/feature checks may precede resource lookup.

### Phase boundary and verification

OPEN -> CANCELLED undoes unused seating; OPEN -> CLOSED ends a served, paid
session. V24 POST /api/v1/orders/{orderId}/complete atomically completes DINE_IN
and closes exactly order.tableSessionId when payment, served-item and session-wide
readiness checks pass. TAKEAWAY completion has no TABLE permission/feature dependency.
POST /api/v1/table-sessions/{sessionId}/close remains available and is idempotent
after automatic closure. Neither closure path creates payment or refunds.
See [kitchen-completion-api.md](kitchen-completion-api.md) and
[FE synchronization](order-completion-session-fe-sync.md).
There is no merge/split, reservation or realtime integration. V21 adds manual PAYMENT
recording separately; see [payment-api.md](payment-api.md).
V20 adds moving an OPEN session to an empty table without changing its orders:
[transfer API](table-session-transfer-api.md) and [FE synchronization](table-session-transfer-fe-sync.md).
ORDER MVP is now available separately in [order-api.md](order-api.md); public menus are documented separately.

## Inventory Phase 1 và 2

V30 adds tenant warehouse management, exactly-at-most-one active default warehouse,
sparse current balances, minimum thresholds and a derived low-stock query. All routes
are under `/api/v1/inventory`, require the effective `INVENTORY_MANAGEMENT` snapshot
feature and the endpoint permission. `/low-stock` additionally requires
`LOW_STOCK_ALERT`. See [inventory-api.md](inventory-api.md) for bodies, responses,
Postman examples, error codes and concurrency behavior.

Tracked ingredients without a physical balance row are returned as logical zero
balances; reads never create rows. `minQuantity=0` disables low-stock. Phase 1 has
no quantity mutation, stock document/transaction, unit conversion, automatic recipe
deduction, persistent alert, stock audit or supplier API. Ingredient base-unit,
tracking removal and deletion are blocked with `INGREDIENT_HAS_STOCK` while any
warehouse has non-zero stock.

V31 adds `RECEIPT`, `ISSUE` and `ADJUSTMENT` stock documents. Draft editing uses
`expectedVersion`; creation uses a tenant-scoped `Idempotency-Key`. Confirm is the
only transition that atomically changes balances and appends immutable inventory
transactions. It locks balances in deterministic ingredient order, forbids negative
stock and rolls the whole document back on any invalid line. Confirm/cancel retries
are no-ops without duplicate ledger or audit. See [inventory-api.md](inventory-api.md).

Staff may now send DINE_IN orders using POST /api/v1/orders with tableId + SUBMIT:
an OPEN session is reused, or seating is opened only when the order is sent.
Opening new seating requires TABLE_OPEN at runtime in addition to ORDER permissions
and TABLE_MANAGEMENT/ORDER_MANAGEMENT. Existing seating needs no TABLE_OPEN.
The TABLE-owned mandatory-transaction writer is shared with manual-open; manual-open
retains table expectedVersion and existing HTTP contract. New seating and its audit
roll back if the order fails. No /orders/from-table route was added.

V16/V17 install a mandatory TableSessionUsagePolicy backed by the ORDER repository.
Order creation and session cancellation share the restaurant transaction/write lock.
Cancellation is blocked when any related order is not CANCELLED, paidAmount > 0,
or paymentStatus is not UNPAID. If all orders are cancelled and have no financial
obligations, seating cancellation is allowed. Usage query failures roll back the
cancellation; a missing adapter prevents startup. This is not bill closure.

V15 adds only the three table-domain tables and constraints; V1-V14 and role grants
are unchanged. TableIntegrationTest creates/drops its own UUID-named PostgreSQL
schema, validates tenant/security/concurrency/QR/audit and batch query behavior.
FlywayCleanMigrationIntegrationTest validates all migrations on a fresh schema.
