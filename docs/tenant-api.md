# Tenant profile and staff API

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
