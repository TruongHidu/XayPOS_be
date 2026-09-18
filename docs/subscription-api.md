# Public package và tenant subscription API

Tài liệu này chỉ mô tả API public/tenant. Contract duy nhất cho toàn bộ `/api/v1/admin/**`, gồm
catalog, restaurant, subscription commands/queries, dashboard và audit, nằm tại
[`admin-api.md`](admin-api.md) và [`openapi/admin-api.yaml`](openapi/admin-api.yaml). Admin paths
không được lặp lại trong OpenAPI này để tránh hai nguồn contract mâu thuẫn.

Base URL local:

```text
http://localhost:8080/api/v1
```

## Endpoint

| Method | Path | Xác thực | Chức năng |
|---|---|---|---|
| GET | `/packages` | Public | Danh sách package active và feature active hiện tại |
| GET | `/packages/{packageCode}` | Public | Chi tiết một package active |
| GET | `/subscriptions/current` | Bearer + `SUBSCRIPTION_VIEW` | Subscription hiệu lực của tenant hiện tại |
| GET | `/subscriptions/history?page=0&size=20` | Bearer + `SUBSCRIPTION_VIEW` | Lịch sử subscription của tenant |
| GET | `/me/entitlements` | Bearer | Entitlement hiệu lực của tenant hiện tại |

Header cho API protected:

```http
Authorization: Bearer <access-token>
```

Tenant ID luôn được lấy từ principal JWT; client không truyền `restaurantId`. Sau khi JWT được xác
thực, mọi protected tenant request còn kiểm tra restaurant tồn tại, chưa soft-delete và có status
`ACTIVE`. Vì vậy access token cũ bị chặn ngay sau khi nhà hàng bị suspend/inactive.

## Public package catalog

`GET /packages` trả array package active theo giá tăng dần. `GET /packages/{packageCode}` trim và
uppercase code; package không tồn tại hoặc inactive đều trả `404 PACKAGE_NOT_FOUND`.

Response:

```json
{
  "id": "21df1bc3-82e0-4f90-a767-cfcbcf9f64fd",
  "code": "PRO",
  "name": "Pro",
  "description": "Operations and staff features",
  "priceAmount": 399000.00,
  "currencyCode": "VND",
  "billingCycleMonths": 1,
  "features": [
    {
      "code": "TABLE_MANAGEMENT",
      "limits": {}
    }
  ]
}
```

Khác admin catalog, public API không trả field `active` và không trả package/feature inactive.

## Subscription hiện tại và entitlement

Subscription được coi là hiệu lực khi:

```text
status == ACTIVE && startAt <= now && now < endAt
```

`GET /subscriptions/current` và `GET /me/entitlements` trả:

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

`features` là immutable snapshot đã chốt lúc activate. Disable feature, đổi package mapping hoặc giá
package về sau không sửa entitlement lịch sử. `features.is_active` không phải global kill switch cho
snapshot đã active.

Không có subscription effective trả business `403 SUBSCRIPTION_NOT_ACTIVE`.

## Lịch sử subscription

```http
GET /api/v1/subscriptions/history?page=0&size=20
```

Response:

```json
{
  "content": [
    {
      "id": "4cd93ccb-04e4-498b-b88c-4fe20f945a88",
      "restaurantId": "9f082839-3dcf-49a4-94fd-b81ab599cd75",
      "packageId": "21df1bc3-82e0-4f90-a767-cfcbcf9f64fd",
      "packageCode": "PRO",
      "status": "EXPIRED",
      "startAt": "2026-07-24T00:00:00Z",
      "endAt": "2026-08-24T00:00:00Z",
      "autoRenew": false,
      "priceAmount": 399000.00,
      "currencyCode": "VND",
      "activatedAt": "2026-07-24T00:00:00Z",
      "cancelledAt": null,
      "features": [
        {"code": "TABLE_MANAGEMENT", "limits": {}}
      ]
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Kết quả sắp xếp `createdAt DESC`. Implementation hiện normalize `page < 0` thành 0, `size < 1`
thành 1 và `size > 100` thành 100.

## Vòng đời và expiration

- Status gồm `PENDING`, `ACTIVE`, `EXPIRED`, `CANCELLED`.
- `startAt` inclusive, `endAt` exclusive.
- Mỗi tenant có tối đa một row lưu status `ACTIVE`, được bảo vệ bởi lock, `@Version` và partial
  unique index PostgreSQL.
- Cơ chế lazy + scheduled reconciliation chuyển `ACTIVE` có `endAt <= now` sang `EXPIRED`; audit
  system chỉ được ghi một lần.
- `autoRenew=true` vẫn expire vì hệ thống chưa có billing/renewal engine; không tự thu tiền hoặc tạo
  subscription mới.
- Downgrade/change package không hard-delete lịch sử và không xóa dữ liệu nghiệp vụ.

Chi tiết command và cấu hình scheduler nằm trong [SUPER_ADMIN API](admin-api.md#subscription-commands).

## Error response

```json
{
  "success": false,
  "code": "RESTAURANT_INACTIVE",
  "message": "Restaurant is not active",
  "fieldErrors": {},
  "timestamp": "2026-08-27T00:00:00Z"
}
```

- `401 UNAUTHORIZED`: access token thiếu/sai/hết hạn.
- `403 FORBIDDEN`: đã login nhưng thiếu permission.
- `403 RESTAURANT_INACTIVE`: restaurant tenant inactive, suspended, soft-delete hoặc không tồn tại.
- `403 SUBSCRIPTION_NOT_ACTIVE`: không có subscription effective.
- `404 PACKAGE_NOT_FOUND`: public package không tồn tại hoặc inactive.
- `400 VALIDATION_ERROR`: query parameter sai kiểu.

OpenAPI canonical: [`openapi/subscription-api.yaml`](openapi/subscription-api.yaml).
