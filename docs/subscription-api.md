# Feature Package, Subscription và Entitlement

Base URL: `http://localhost:8080/api/v1`.

## Tạo tài khoản SUPER_ADMIN lần đầu

Bootstrap mặc định tắt và không có mật khẩu hard-code. Trên PowerShell, đặt biến môi trường rồi chạy ứng dụng một lần:

```powershell
$env:SUPER_ADMIN_BOOTSTRAP_ENABLED = 'true'
$env:SUPER_ADMIN_EMAIL = 'admin@kiottay.vn'
$env:SUPER_ADMIN_PASSWORD = 'ChangeThisStrongPassword!'
$env:SUPER_ADMIN_NAME = 'System Administrator'
mvn spring-boot:run
```

Bootstrap idempotent theo email. Sau khi tài khoản đã được tạo, tắt `SUPER_ADMIN_BOOTSTRAP_ENABLED` và đăng nhập bằng `POST /api/v1/auth/login` để lấy Bearer token.

## Mô hình authorization

API nghiệp vụ sử dụng đồng thời hai điều kiện độc lập:

```java
@PreAuthorize("@featureSecurity.hasCurrentTenantFeature('MENU_MANAGEMENT')"
    + " and hasAuthority('MENU_CREATE')")
public MenuItemResponse createMenuItem(...) {
    // restaurantId luôn lấy từ CurrentTenantProvider;
    // repository tiếp tục lọc theo restaurantId để bảo vệ ownership.
}
```

`SUPER_ADMIN` không tự động bypass entitlement của tenant. API quản trị subscription yêu cầu đồng thời role `SUPER_ADMIN` và authority `SUBSCRIPTION_MANAGE`. API tenant không nhận `restaurantId` từ client.

## API

| Method | Path | Xác thực | Chức năng |
|---|---|---|---|
| GET | `/packages` | Public | Danh sách package active và feature hiện tại của package |
| GET | `/packages/{packageCode}` | Public | Chi tiết một package active |
| GET | `/subscriptions/current` | Bearer + `SUBSCRIPTION_VIEW` | Subscription hiệu lực của tenant hiện tại |
| GET | `/subscriptions/history?page=0&size=20` | Bearer + `SUBSCRIPTION_VIEW` | Lịch sử subscription của tenant, tối đa 100 dòng/trang |
| GET | `/me/entitlements` | Bearer | Entitlement hiệu lực của tenant hiện tại |
| POST | `/admin/restaurants/{restaurantId}/subscriptions` | Bearer + `SUPER_ADMIN` + `SUBSCRIPTION_MANAGE` | Tạo subscription `PENDING` |
| POST | `/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}/activate` | Như trên | Activate và chốt snapshot |
| POST | `/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}/change-package` | Như trên | Kết thúc gói cũ, tạo gói mới trong cùng transaction |
| POST | `/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}/cancel` | Như trên | Hủy idempotent, không hard delete |
| GET | `/admin/features?includeInactive=true` | Bearer + `SUPER_ADMIN` + `PACKAGE_VIEW` | Danh sách feature, gồm feature inactive nếu yêu cầu |
| POST | `/admin/features` | Bearer + `SUPER_ADMIN` + `PACKAGE_MANAGE` | Tạo feature active |
| PUT | `/admin/features/{featureCode}` | Như trên | Cập nhật tên, mô tả và trạng thái feature |
| GET | `/admin/packages?includeInactive=true` | Bearer + `SUPER_ADMIN` + `PACKAGE_VIEW` | Danh sách package quản trị |
| POST | `/admin/packages` | Bearer + `SUPER_ADMIN` + `PACKAGE_MANAGE` | Tạo package active |
| PUT | `/admin/packages/{packageCode}` | Như trên | Cập nhật giá, chu kỳ và trạng thái package |
| POST | `/admin/packages/{packageCode}/features/{featureCode}` | Như trên | Thêm feature và JSON limits vào package |
| DELETE | `/admin/packages/{packageCode}/features/{featureCode}` | Như trên | Xóa mapping; không sửa snapshot cũ |

Header cho API có xác thực:

```http
Authorization: Bearer <access-token>
Content-Type: application/json
```

### Tạo subscription

```json
{
  "packageCode": "PRO",
  "startAt": "2026-08-24T00:00:00Z",
  "endAt": "2026-09-24T00:00:00Z",
  "autoRenew": false,
  "priceAmount": 399000.00,
  "currencyCode": "VND"
}
```

Response `201 Created`:

```json
{
  "id": "4cd93ccb-04e4-498b-b88c-4fe20f945a88",
  "restaurantId": "9f082839-3dcf-49a4-94fd-b81ab599cd75",
  "packageId": "21df1bc3-82e0-4f90-a767-cfcbcf9f64fd",
  "packageCode": "PRO",
  "status": "PENDING",
  "startAt": "2026-08-24T00:00:00Z",
  "endAt": "2026-09-24T00:00:00Z",
  "autoRenew": false,
  "priceAmount": 399000.00,
  "currencyCode": "VND",
  "activatedAt": null,
  "cancelledAt": null,
  "features": []
}
```

Client không thể gán `featureSnapshot`, `activatedAt`, `cancelledAt`, `createdAt`, `updatedAt` hoặc `version`; các trường này không tồn tại trong request DTO.

### Đổi package

```json
{
  "packageCode": "PREMIUM",
  "endAt": "2026-10-24T00:00:00Z",
  "autoRenew": true,
  "priceAmount": 699000.00,
  "currencyCode": "VND"
}
```

Response `200 OK` là subscription `ACTIVE` mới. Subscription cũ được giữ lại ở trạng thái `CANCELLED`, snapshot cũ không thay đổi.

### Entitlement hiện tại

```json
{
  "subscriptionId": "4cd93ccb-04e4-498b-b88c-4fe20f945a88",
  "packageCode": "PRO",
  "status": "ACTIVE",
  "startAt": "2026-08-24T00:00:00Z",
  "endAt": "2026-09-24T00:00:00Z",
  "features": [
    {
      "code": "TABLE_MANAGEMENT",
      "limits": {}
    }
  ]
}
```

## Error response

```json
{
  "success": false,
  "code": "FEATURE_NOT_ENTITLED",
  "message": "The current package does not include this feature",
  "fieldErrors": {},
  "timestamp": "2026-08-24T00:00:00Z"
}
```

Các mã chính:

- `400`: `VALIDATION_ERROR`, `INVALID_REQUEST_BODY`, `INVALID_SUBSCRIPTION_PERIOD`, `PACKAGE_INACTIVE`.
- `403`: `FORBIDDEN`, `TENANT_ACCESS_DENIED`, `SUBSCRIPTION_NOT_ACTIVE`, `FEATURE_NOT_ENTITLED`.
- `404`: `PACKAGE_NOT_FOUND`, `RESTAURANT_NOT_FOUND`, `SUBSCRIPTION_NOT_FOUND`. Truy vấn khác tenant cũng trả `SUBSCRIPTION_NOT_FOUND`.
- `409`: `SUBSCRIPTION_ALREADY_ACTIVE`, `SUBSCRIPTION_OVERLAP`, `INVALID_SUBSCRIPTION_TRANSITION`, `CONCURRENT_SUBSCRIPTION_UPDATE`.

## Snapshot và vòng đời

- Snapshot schema version hiện tại là `1`, gồm `packageCode`, `features`, từng `limits`, và `capturedAt` UTC.
- Chỉ feature active tại thời điểm activate được chụp. Sau đó snapshot là nguồn entitlement duy nhất.
- Không có global kill switch ở phiên bản này; tắt feature chỉ ảnh hưởng snapshot được tạo sau đó.
- `startAt` inclusive, `endAt` exclusive. Scheduler đổi trạng thái `EXPIRED` không cần thiết để chặn truy cập sau `endAt`.
- Change package khóa tenant, cancel subscription cũ và tạo subscription active mới trong một transaction.
- Cancel gọi lại trả cùng trạng thái `CANCELLED` và không ghi audit trùng.
- Các action audit: `SUBSCRIPTION_CREATED`, `SUBSCRIPTION_ACTIVATED`, `SUBSCRIPTION_PACKAGE_CHANGED`, `SUBSCRIPTION_CANCELLED`.

## Ma trận seed mặc định

- BASIC: feature POS lõi; không có `TABLE_MANAGEMENT`.
- PRO: toàn bộ BASIC và quản lý bàn/nhân viên/KDS; không có `INVENTORY_MANAGEMENT`.
- PREMIUM: toàn bộ PRO và inventory/analytics/AI.

Mapping nằm hoàn toàn trong database migration, không có `if/else` theo package code trong Java.

## Request quản trị catalog

Tạo feature:

```json
{
  "code": "LOYALTY_MANAGEMENT",
  "name": "Quản lý khách hàng thân thiết",
  "description": "Điểm và hạng thành viên"
}
```

Tạo package:

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

Thêm feature vào package:

```json
{
  "limits": {
    "maxLocations": 10,
    "monthlyRequests": 100000
  }
}
```

Catalog là tài nguyên toàn hệ thống nên audit của thao tác package/feature có `restaurant_id = null`; `actor_user_id` vẫn bắt buộc trỏ đến tài khoản `SUPER_ADMIN` đang thực hiện. Snapshot của các tenant đã activate không đổi khi feature bị disable hoặc mapping bị xóa.
