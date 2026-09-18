# SUPER_ADMIN API

Tài liệu này là contract dành cho web quản trị hệ thống. OpenAPI canonical của toàn bộ
`/api/v1/admin/**` nằm tại [`openapi/admin-api.yaml`](openapi/admin-api.yaml). Các API public và
tenant của module subscription nằm riêng tại [`subscription-api.md`](subscription-api.md).

Base URL local:

```text
http://localhost:8080/api/v1
```

## Xác thực và phân quyền

Mọi API trong tài liệu này yêu cầu:

```http
Authorization: Bearer <access-token>
```

Với request có body JSON, gửi thêm:

```http
Content-Type: application/json
```

Token chỉ được chấp nhận cho quản trị hệ thống khi principal đồng thời có role chính xác
`SUPER_ADMIN`, không thuộc nhà hàng nào (`restaurantId == null`) và có permission của endpoint.
Role hoặc permission riêng lẻ không đủ. OWNER được gán một permission trùng tên, hoặc user tenant
có role `SUPER_ADMIN`, vẫn nhận `403 FORBIDDEN`.

Migration mới thêm `ADMIN_DASHBOARD_VIEW`, `RESTAURANT_VIEW`, `RESTAURANT_MANAGE` và `AUDIT_VIEW`
cho role `SUPER_ADMIN` toàn hệ thống. Admin đã đăng nhập trước khi migration chạy phải login lại hoặc
refresh token để JWT chứa các permission mới.

| Nhóm API | Permission |
|---|---|
| Dashboard summary | `ADMIN_DASHBOARD_VIEW` |
| Restaurant list/detail | `RESTAURANT_VIEW` |
| Thay đổi trạng thái restaurant | `RESTAURANT_MANAGE` |
| Subscription list/detail | `SUBSCRIPTION_VIEW` |
| Subscription create/activate/change/cancel | `SUBSCRIPTION_MANAGE` |
| Audit log | `AUDIT_VIEW` |
| Feature/package list/detail | `PACKAGE_VIEW` |
| Feature/package mutation | `PACKAGE_MANAGE` |

Lỗi security luôn là JSON:

```json
{
  "success": false,
  "code": "UNAUTHORIZED",
  "message": "Authentication is required",
  "fieldErrors": {},
  "timestamp": "2026-08-27T00:00:00Z"
}
```

- Thiếu, sai hoặc hết hạn access token: `401 UNAUTHORIZED`.
- Đã xác thực nhưng không phải system SUPER_ADMIN hoặc thiếu permission: `403 FORBIDDEN`.

## Danh sách endpoint

| Method | Path | Permission | Chức năng |
|---|---|---|---|
| GET | `/admin/dashboard/summary` | `ADMIN_DASHBOARD_VIEW` | Số liệu tổng hợp tại thời điểm hiện tại |
| GET | `/admin/restaurants` | `RESTAURANT_VIEW` | Tìm kiếm, lọc và phân trang restaurant |
| GET | `/admin/restaurants/{restaurantId}` | `RESTAURANT_VIEW` | Chi tiết restaurant, owners, user counts và subscription |
| PATCH | `/admin/restaurants/{restaurantId}/status` | `RESTAURANT_MANAGE` | Active/inactive/suspend restaurant |
| GET | `/admin/subscriptions` | `SUBSCRIPTION_VIEW` | Subscription toàn hệ thống |
| GET | `/admin/restaurants/{restaurantId}/subscriptions` | `SUBSCRIPTION_VIEW` | Subscription của một restaurant |
| GET | `/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}` | `SUBSCRIPTION_VIEW` | Chi tiết và immutable feature snapshot |
| POST | `/admin/restaurants/{restaurantId}/subscriptions` | `SUBSCRIPTION_MANAGE` | Tạo subscription `PENDING` |
| POST | `/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}/activate` | `SUBSCRIPTION_MANAGE` | Activate và chốt feature snapshot |
| POST | `/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}/change-package` | `SUBSCRIPTION_MANAGE` | Kết thúc gói hiện tại và tạo gói thay thế |
| POST | `/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}/cancel` | `SUBSCRIPTION_MANAGE` | Cancel idempotent |
| GET | `/admin/audit-logs` | `AUDIT_VIEW` | Đọc audit log bất biến |
| GET | `/admin/features` | `PACKAGE_VIEW` | Danh sách feature |
| GET | `/admin/features/{featureCode}` | `PACKAGE_VIEW` | Chi tiết feature, kể cả inactive |
| POST | `/admin/features` | `PACKAGE_MANAGE` | Tạo feature |
| PUT | `/admin/features/{featureCode}` | `PACKAGE_MANAGE` | Cập nhật feature |
| GET | `/admin/packages` | `PACKAGE_VIEW` | Danh sách package |
| GET | `/admin/packages/{packageCode}` | `PACKAGE_VIEW` | Chi tiết package, kể cả inactive |
| POST | `/admin/packages` | `PACKAGE_MANAGE` | Tạo package |
| PUT | `/admin/packages/{packageCode}` | `PACKAGE_MANAGE` | Cập nhật package |
| POST | `/admin/packages/{packageCode}/features/{featureCode}` | `PACKAGE_MANAGE` | Gắn feature và limits vào package |
| DELETE | `/admin/packages/{packageCode}/features/{featureCode}` | `PACKAGE_MANAGE` | Gỡ mapping feature khỏi package |

## Restaurant

### List

```http
GET /api/v1/admin/restaurants?q=kiot&status=ACTIVE&page=0&size=20&sortBy=createdAt&direction=desc
```

| Query | Kiểu/default | Quy tắc |
|---|---|---|
| `q` | string, optional | Trim, tối đa 100; tìm không phân biệt hoa thường trên code, name, legalName và phone |
| `status` | enum, optional | `ACTIVE`, `INACTIVE`, `SUSPENDED` |
| `page` | integer, `0` | Tối thiểu 0 |
| `size` | integer, `20` | Từ 1 đến 100 |
| `sortBy` | string, `createdAt` | `createdAt`, `updatedAt`, `code`, `name`, `status` |
| `direction` | string, `desc` | `asc` hoặc `desc` |

Thứ tự luôn có `id` làm tie-breaker. Restaurant soft-delete không xuất hiện.
`effectiveSubscription` chỉ khác `null` khi subscription có status `ACTIVE` và thỏa
`startAt <= now < endAt`; dữ liệu package/subscription được lấy theo batch, không query từng dòng.

Response `200`:

```json
{
  "content": [
    {
      "id": "9f082839-3dcf-49a4-94fd-b81ab599cd75",
      "code": "KIOTTAY01",
      "name": "Kiot Tay Restaurant",
      "legalName": "Kiot Tay Company",
      "phone": "0900000000",
      "timezone": "Asia/Ho_Chi_Minh",
      "currencyCode": "VND",
      "status": "ACTIVE",
      "createdAt": "2026-08-01T00:00:00Z",
      "updatedAt": "2026-08-27T00:00:00Z",
      "effectiveSubscription": {
        "id": "4cd93ccb-04e4-498b-b88c-4fe20f945a88",
        "packageCode": "PRO",
        "status": "ACTIVE",
        "startAt": "2026-08-01T00:00:00Z",
        "endAt": "2026-09-01T00:00:00Z",
        "autoRenew": false
      }
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`legalName`, `phone` và `effectiveSubscription` có thể `null`. Response không chứa `settings`,
`publicOrderToken` hoặc `deletedAt`.

### Detail

```http
GET /api/v1/admin/restaurants/{restaurantId}
```

Response `200`:

```json
{
  "id": "9f082839-3dcf-49a4-94fd-b81ab599cd75",
  "code": "KIOTTAY01",
  "name": "Kiot Tay Restaurant",
  "legalName": "Kiot Tay Company",
  "phone": "0900000000",
  "address": "Ho Chi Minh City",
  "timezone": "Asia/Ho_Chi_Minh",
  "currencyCode": "VND",
  "status": "ACTIVE",
  "createdAt": "2026-08-01T00:00:00Z",
  "updatedAt": "2026-08-27T00:00:00Z",
  "owners": [
    {
      "id": "bd72932d-e947-469f-b235-af6282de20af",
      "name": "Nguyen Van A",
      "email": "owner@example.com",
      "phone": "0911111111",
      "active": true,
      "lastLoginAt": "2026-08-27T00:00:00Z"
    }
  ],
  "userCounts": {"total": 5, "active": 4},
  "effectiveSubscription": null,
  "latestSubscription": {
    "id": "4cd93ccb-04e4-498b-b88c-4fe20f945a88",
    "packageCode": "PRO",
    "status": "EXPIRED",
    "startAt": "2026-07-01T00:00:00Z",
    "endAt": "2026-08-01T00:00:00Z",
    "autoRenew": false
  }
}
```

`owners` luôn là array vì schema không giới hạn một OWNER. `legalName`, `phone`, `address`,
`lastLoginAt`, `effectiveSubscription` và `latestSubscription` có thể `null`. Password hash không
được trả. UUID sai định dạng trả `400 VALIDATION_ERROR`; không tồn tại hoặc soft-delete trả
`404 RESTAURANT_NOT_FOUND`.

### Thay đổi trạng thái

```http
PATCH /api/v1/admin/restaurants/{restaurantId}/status
Content-Type: application/json

{
  "status": "SUSPENDED",
  "reason": "Subscription payment verification required"
}
```

`status` và `reason` bắt buộc; `reason` được trim, không được blank và tối đa 500 ký tự. Response
`200` là restaurant detail như trên.

Command khóa restaurant bằng pessimistic write. Nếu status không đổi, API trả dữ liệu hiện tại và
không ghi audit/revoke lại. Nếu thay đổi, audit `RESTAURANT_STATUS_CHANGED` lưu status trước, status
sau và reason. Chuyển từ `ACTIVE` sang `INACTIVE` hoặc `SUSPENDED` revoke mọi refresh token đang hoạt
động của tenant. Access token cũ cũng bị chặn ngay trên request protected tiếp theo với
`403 RESTAURANT_INACTIVE`. Reactivate không phát token; user phải login lại. Refresh token đã revoke
trả `401 INVALID_REFRESH_TOKEN`.

Không có hard-delete restaurant trong phase này.

## Subscription query

### List toàn hệ thống

```http
GET /api/v1/admin/subscriptions?q=kiot&restaurantId={uuid}&packageCode=pro&status=ACTIVE&effective=true&page=0&size=20&sortBy=createdAt&direction=desc
```

| Query | Kiểu/default | Quy tắc |
|---|---|---|
| `q` | string, optional | Trim, tối đa 100; tìm code/name restaurant không phân biệt hoa thường |
| `restaurantId` | UUID, optional | Restaurant cụ thể |
| `packageCode` | string, optional | Trim và uppercase |
| `status` | enum, optional | `PENDING`, `ACTIVE`, `EXPIRED`, `CANCELLED` |
| `effective` | boolean, optional | `true` khi `ACTIVE && startAt <= now < endAt`, ngược lại là `false` |
| `page` | integer, `0` | Tối thiểu 0 |
| `size` | integer, `20` | Từ 1 đến 100 |
| `sortBy` | string, `createdAt` | `createdAt`, `startAt`, `endAt`, `status`, `priceAmount` |
| `direction` | string, `desc` | `asc` hoặc `desc` |

Pagination chạy tại database, chỉ lấy subscription của restaurant chưa soft-delete và thêm `id` làm
tie-breaker.

Response `200`:

```json
{
  "content": [
    {
      "id": "4cd93ccb-04e4-498b-b88c-4fe20f945a88",
      "restaurant": {
        "id": "9f082839-3dcf-49a4-94fd-b81ab599cd75",
        "code": "KIOTTAY01",
        "name": "Kiot Tay Restaurant",
        "status": "ACTIVE"
      },
      "packageInfo": {
        "id": "21df1bc3-82e0-4f90-a767-cfcbcf9f64fd",
        "code": "PRO",
        "name": "Pro"
      },
      "status": "ACTIVE",
      "effective": true,
      "startAt": "2026-08-01T00:00:00Z",
      "endAt": "2026-09-01T00:00:00Z",
      "autoRenew": false,
      "priceAmount": 399000.00,
      "currencyCode": "VND",
      "activatedAt": "2026-08-01T00:00:00Z",
      "cancelledAt": null,
      "createdAt": "2026-08-01T00:00:00Z",
      "updatedAt": "2026-08-01T00:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`activatedAt` và `cancelledAt` có thể `null`.

### List theo restaurant

```http
GET /api/v1/admin/restaurants/{restaurantId}/subscriptions?status=ACTIVE&packageCode=PRO&page=0&size=20
```

`status` và `packageCode` optional; `page >= 0`, `size` 1–100. Thứ tự cố định là
`createdAt DESC, id DESC`. API xác nhận restaurant chưa soft-delete trước khi query; nếu không tìm
thấy trả `404 RESTAURANT_NOT_FOUND`. Response dùng cùng page/summary schema với list toàn hệ thống.

### Detail

```http
GET /api/v1/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}
```

Backend query bằng cả `restaurantId` và `subscriptionId`. Một subscription tồn tại nhưng thuộc
restaurant khác vẫn trả `404 SUBSCRIPTION_NOT_FOUND`.

Response `200` có toàn bộ field của summary và thêm:

```json
{
  "features": [
    {
      "code": "POS_QUICK_ORDER",
      "limits": {}
    }
  ]
}
```

`features` được đọc từ immutable snapshot đã chốt lúc activate, không đọc lại package catalog hiện
tại. Vì vậy thay đổi/disable feature hoặc gỡ mapping sau đó không làm thay đổi entitlement lịch sử.

## Subscription commands

### Tạo PENDING

```http
POST /api/v1/admin/restaurants/{restaurantId}/subscriptions

{
  "packageCode": "PRO",
  "startAt": "2026-08-27T00:00:00Z",
  "endAt": "2026-09-27T00:00:00Z",
  "autoRenew": false,
  "priceAmount": 399000.00,
  "currencyCode": "VND"
}
```

`packageCode`, `startAt`, `endAt`, `priceAmount`, `currencyCode` bắt buộc;
`endAt > startAt`, `priceAmount >= 0`, currency đúng 3 chữ cái. Code/currency được normalize
uppercase. Response `201` là `SubscriptionResponse`:

```json
{
  "id": "4cd93ccb-04e4-498b-b88c-4fe20f945a88",
  "restaurantId": "9f082839-3dcf-49a4-94fd-b81ab599cd75",
  "packageId": "21df1bc3-82e0-4f90-a767-cfcbcf9f64fd",
  "packageCode": "PRO",
  "status": "PENDING",
  "startAt": "2026-08-27T00:00:00Z",
  "endAt": "2026-09-27T00:00:00Z",
  "autoRenew": false,
  "priceAmount": 399000.00,
  "currencyCode": "VND",
  "activatedAt": null,
  "cancelledAt": null,
  "features": []
}
```

### Activate

```http
POST /api/v1/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}/activate
```

Chỉ `PENDING` được activate. Feature active của package được chốt vào snapshot. Trước khi activate,
lazy reconciliation chuyển subscription `ACTIVE` cũ có `endAt <= now` sang `EXPIRED`, flush và tiếp
tục; nếu row ACTIVE cũ chưa hết hạn trả `409 SUBSCRIPTION_OVERLAP`. Response `200` có status
`ACTIVE` và snapshot trong `features`.

### Change package

```http
POST /api/v1/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}/change-package

{
  "packageCode": "PREMIUM",
  "endAt": "2026-10-27T00:00:00Z",
  "autoRenew": true,
  "priceAmount": 699000.00,
  "currencyCode": "VND"
}
```

Subscription đích phải đang effective. Gói cũ chuyển `CANCELLED`, gói mới `ACTIVE` được tạo trong
cùng transaction với snapshot mới; lịch sử/snapshot cũ được giữ lại. Response `200` là subscription
mới.

### Cancel

```http
POST /api/v1/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}/cancel
```

Cancel trả `200`. Gọi lại một row `CANCELLED` là idempotent và không tạo audit trùng. Row `EXPIRED`
không thể cancel. PENDING có thể chuyển sang CANCELLED.

### Expiration

Mọi transition `ACTIVE + endAt <= now -> EXPIRED` đi qua `SubscriptionExpirationService`:

- Lazy reconciliation chạy trước activate và trước các command cần kiểm tra trạng thái.
- Scheduler mặc định bắt đầu sau một phút, chạy mỗi phút, tối đa 100 row/lần, dùng lock + batch và
  idempotent audit `SUBSCRIPTION_EXPIRED` với `actorUserId = null`.
- `autoRenew=true` vẫn hết hạn; hệ thống chưa tự thu tiền hoặc tạo subscription mới.
- Snapshot và `cancelledAt` không bị thay đổi khi expire.

Cấu hình:

```text
SUBSCRIPTION_EXPIRATION_ENABLED=true
SUBSCRIPTION_EXPIRATION_FIXED_DELAY=PT1M
SUBSCRIPTION_EXPIRATION_INITIAL_DELAY=PT1M
SUBSCRIPTION_EXPIRATION_BATCH_SIZE=100
```

## Dashboard

```http
GET /api/v1/admin/dashboard/summary
```

Response `200`:

```json
{
  "generatedAt": "2026-08-27T00:00:00Z",
  "restaurants": {
    "total": 20,
    "active": 16,
    "inactive": 2,
    "suspended": 2,
    "newLast30Days": 3,
    "activeWithoutEffectiveSubscription": 4
  },
  "subscriptions": {
    "total": 30,
    "pending": 4,
    "activeStatus": 12,
    "effectiveNow": 10,
    "staleActive": 1,
    "expired": 6,
    "cancelled": 8,
    "expiringWithin7Days": 2
  },
  "packages": {"total": 3, "active": 3, "inactive": 0},
  "features": {"total": 23, "active": 23, "inactive": 0}
}
```

Các count dùng aggregation tại database và Clock chung:

- `activeStatus`: số row lưu status ACTIVE.
- `effectiveNow`: ACTIVE và `startAt <= generatedAt < endAt`.
- `staleActive`: ACTIVE nhưng `endAt <= generatedAt`.
- `expiringWithin7Days`: effective tại `generatedAt` và `endAt <= generatedAt + 7 ngày`.
- `activeWithoutEffectiveSubscription`: restaurant ACTIVE không có subscription effective.
- `newLast30Days`: restaurant chưa soft-delete, tạo từ `generatedAt - 30 ngày`.

Các count phù hợp loại restaurant soft-delete. Dashboard không có revenue, MRR, ARR, churn hoặc
payment stats; `priceAmount` không được suy diễn thành doanh thu.

## Audit log

```http
GET /api/v1/admin/audit-logs?scope=ALL&restaurantId={uuid}&actorUserId={uuid}&actionCode=SUBSCRIPTION_ACTIVATED&entityType=restaurant_subscriptions&entityId={uuid}&from=2026-08-01T00:00:00Z&to=2026-09-01T00:00:00Z&page=0&size=20
```

| Query | Quy tắc |
|---|---|
| `scope` | `ALL` (default), `SYSTEM` (`restaurantId IS NULL`) hoặc `TENANT` (`IS NOT NULL`) |
| `restaurantId`, `actorUserId`, `entityId` | UUID optional |
| `actionCode` | Exact match sau trim + uppercase, tối đa 100 |
| `entityType` | Exact match sau trim, tối đa 80 |
| `from` | ISO-8601 instant inclusive |
| `to` | ISO-8601 instant exclusive |
| `page`, `size` | `page >= 0`, `size` 1–100; default 0/20 |

`to <= from` trả `400 INVALID_AUDIT_PERIOD`; kết hợp `scope=SYSTEM` và `restaurantId` trả
`400 INVALID_AUDIT_FILTER`. Sort cố định `createdAt DESC, id DESC`.

Response `200`:

```json
{
  "content": [
    {
      "id": "5ce182f6-63e9-4741-a94d-3771f223bbbd",
      "restaurantId": "9f082839-3dcf-49a4-94fd-b81ab599cd75",
      "restaurantCode": "KIOTTAY01",
      "restaurantName": "Kiot Tay Restaurant",
      "actorUserId": "bd72932d-e947-469f-b235-af6282de20af",
      "actorName": "System Administrator",
      "actorEmail": "admin@example.com",
      "actionCode": "SUBSCRIPTION_ACTIVATED",
      "entityType": "restaurant_subscriptions",
      "entityId": "4cd93ccb-04e4-498b-b88c-4fe20f945a88",
      "beforeData": {"status": "PENDING"},
      "afterData": {"status": "ACTIVE", "packageCode": "PRO"},
      "ipAddress": "127.0.0.1",
      "createdAt": "2026-08-27T00:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Restaurant, actor, entity, before/after data và IP có thể `null`. System job có actor `null`.
`beforeData`/`afterData` được redact recursive cả trước khi ghi lẫn lúc đọc. Các key nhạy cảm
không phân biệt hoa thường/camel/snake/kebab (`password`, `passwordHash`, `token`, `accessToken`,
`refreshToken`, `tokenHash`, `authorization`, `secret`, `publicOrderToken`) có giá trị
`[REDACTED]`. IP lấy từ remote address; ứng dụng không tin trực tiếp `X-Forwarded-For` khi chưa cấu
hình trusted proxy.

Audit là append-only: không có POST, PUT, PATCH hoặc DELETE endpoint.

## Feature và package

Code trong path/request được trim và uppercase. Admin detail/list có thể trả tài nguyên inactive;
khác với public package API chỉ trả package active.

### Feature

```http
GET /api/v1/admin/features?includeInactive=true
GET /api/v1/admin/features/{featureCode}
```

Response là array hoặc một object:

```json
{
  "id": "c16e4127-cfcb-4bed-80b2-754c7323fa2f",
  "code": "POS_QUICK_ORDER",
  "name": "POS quick order",
  "description": null,
  "active": true
}
```

Không tồn tại trả `404 FEATURE_NOT_FOUND`.

Create `POST /admin/features`:

```json
{
  "code": "LOYALTY_MANAGEMENT",
  "name": "Quản lý khách hàng thân thiết",
  "description": "Điểm và hạng thành viên"
}
```

`code` bắt đầu bằng chữ, chỉ gồm chữ/số/underscore, tối đa 100; `name` tối đa 150. Thành công trả
`201`; trùng code trả `409 FEATURE_ALREADY_EXISTS`.

Update `PUT /admin/features/{featureCode}`:

```json
{
  "name": "Quản lý khách hàng thân thiết",
  "description": null,
  "active": false
}
```

`name` và `active` bắt buộc; thành công trả `200`.

### Package

```http
GET /api/v1/admin/packages?includeInactive=true
GET /api/v1/admin/packages/{packageCode}
```

Response là array hoặc một object:

```json
{
  "id": "21df1bc3-82e0-4f90-a767-cfcbcf9f64fd",
  "code": "PRO",
  "name": "Pro",
  "description": "Operations and staff features",
  "priceAmount": 399000.00,
  "currencyCode": "VND",
  "billingCycleMonths": 1,
  "active": true,
  "features": [
    {"code": "POS_QUICK_ORDER", "limits": {}}
  ]
}
```

Không tồn tại trả `404 PACKAGE_NOT_FOUND`.

Create `POST /admin/packages`:

```json
{
  "code": "ENTERPRISE",
  "name": "Enterprise",
  "description": "Gói tùy chỉnh",
  "priceAmount": 1299000,
  "currencyCode": "VND",
  "billingCycleMonths": 1
}
```

`code` tối đa 50 và cùng pattern feature code; `name` tối đa 100; price không âm; currency đúng ba
chữ cái; billing cycle từ 1 đến 120. Thành công trả `201`; trùng code trả
`409 PACKAGE_ALREADY_EXISTS`.

Update `PUT /admin/packages/{packageCode}` dùng body:

```json
{
  "name": "Enterprise",
  "description": "Gói tùy chỉnh",
  "priceAmount": 1399000,
  "currencyCode": "VND",
  "billingCycleMonths": 1,
  "active": true
}
```

Gắn feature bằng `POST /admin/packages/{packageCode}/features/{featureCode}` với body
`{"limits": {}}`; feature inactive trả `400 FEATURE_DISABLED`, mapping trùng trả
`409 PACKAGE_FEATURE_ALREADY_EXISTS`. Gỡ mapping bằng `DELETE` cùng path; mapping không tồn tại trả
`404 PACKAGE_FEATURE_NOT_FOUND`. Hai thao tác trả package đã cập nhật và không sửa snapshot cũ.

## Error contract

Mọi lỗi nghiệp vụ/validation dùng schema:

```json
{
  "success": false,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "fieldErrors": {},
  "timestamp": "2026-08-27T00:00:00Z"
}
```

| HTTP | Code thường gặp |
|---|---|
| 400 | `VALIDATION_ERROR`, `INVALID_REQUEST_BODY`, `INVALID_SUBSCRIPTION_PERIOD`, `INVALID_AUDIT_PERIOD`, `INVALID_AUDIT_FILTER`, `PACKAGE_INACTIVE`, `FEATURE_DISABLED` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN`, `RESTAURANT_INACTIVE` (tenant protected API) |
| 404 | `RESTAURANT_NOT_FOUND`, `SUBSCRIPTION_NOT_FOUND`, `FEATURE_NOT_FOUND`, `PACKAGE_NOT_FOUND`, `PACKAGE_FEATURE_NOT_FOUND` |
| 409 | `FEATURE_ALREADY_EXISTS`, `PACKAGE_ALREADY_EXISTS`, `PACKAGE_FEATURE_ALREADY_EXISTS`, `SUBSCRIPTION_ALREADY_ACTIVE`, `SUBSCRIPTION_OVERLAP`, `SUBSCRIPTION_NOT_ACTIVE`, `INVALID_SUBSCRIPTION_TRANSITION`, `CONCURRENT_SUBSCRIPTION_UPDATE`, `CONFLICT` |
| 500 | `INTERNAL_ERROR` |

UUID/enum/boolean/date-time sai, thiếu query bắt buộc, page/size/sort/direction không hợp lệ trả
`400 VALIDATION_ERROR`. JSON sai cú pháp, unknown field hoặc enum sai trong body trả
`400 INVALID_REQUEST_BODY`.

## Giới hạn chưa hỗ trợ

- Không hard-delete restaurant, package hoặc feature.
- Không impersonation, CRUD SUPER_ADMIN, reset password hoặc MFA.
- Không staff CRUD, billing/payment hoặc auto-renew engine.
- Không revenue/MRR/ARR/churn/payment statistics.
- Không sửa/xóa audit log.
- Không public QR API, restaurant settings editor hoặc public order token management.
