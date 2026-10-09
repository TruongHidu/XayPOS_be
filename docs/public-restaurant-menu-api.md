# Public restaurant menu / takeaway browsing

Menu cấp nhà hàng dùng chung `items`/`item_groups` và giá bán với [menu QR bàn](public-qr-menu-api.md). Khách có thể mở link hoặc quét QR chung để xem thực đơn mà không cần tài khoản, bàn, khu vực hoặc phiên bàn. Giai đoạn này chưa tạo đơn mang đi, giỏ hàng hoặc thanh toán.

OpenAPI: [public-restaurant-menu-api.yaml](openapi/public-restaurant-menu-api.yaml).

API public dùng `/api/v1/public/menu/restaurants/{menuToken}` và suffix `/items`. Đường dẫn API cũ `/api/v1/public/menu/{token}` được thay thế; đường dẫn frontend `/menu/{menuToken}` và API authenticated quản lý link không đổi.

Files changed and actual verification results: [implementation report](public-restaurant-menu-implementation.md).

## Token và luồng sử dụng

`menuToken` / `restaurantMenuToken` ánh xạ tới `restaurants.public_order_token`. Khi đăng ký nhà hàng, backend đã sinh token bằng SecureRandom, 32 bytes, base64url không padding (43 ký tự). Luồng mới tận dụng token này và unique constraint hiện có; không đổi token cũ, không backfill, không thêm migration.

1. OWNER, MANAGER hoặc tenant user có quyền `RESTAURANT_PROFILE_UPDATE` lấy link bằng API authenticated.
2. Nhà hàng có token null khởi tạo bằng POST; GET không sinh token.
3. Frontend dự kiến sử dụng `/menu/{menuToken}`; ghép với origin web khi chia sẻ/in QR.
4. Khách gọi hai GET public, không gửi Authorization.
5. Backend resolve nhà hàng và kiểm tra subscription snapshot rồi đọc menu chung.

Đường dẫn web này là contract dự kiến; thay đổi backend không tự triển khai route frontend. Token chỉ xác định ngữ cảnh nhà hàng để xem menu, không cấp quyền tạo order/phiên/thanh toán. Token bàn được tra ở `restaurant_tables.qr_token`; token nhà hàng được tra ở `restaurants.public_order_token`. Hai API không đổi hoặc tìm token trong namespace của nhau.

## Public access

Headers:

```http
Accept: application/json
```

Không có body, JWT, tenant header hoặc restaurantId. Bearer token nếu đính kèm bị bỏ qua chỉ ở các GET public menu được khai báo chính xác; JWT của nhà hàng khác, inactive, expired hoặc malformed không chọn/chặn ngữ cảnh của link. Mutation và API nội bộ giữ nguyên authentication/permission.

Restaurant phải ACTIVE, undeleted. Bàn/khu vực bị khóa, bị xóa hoặc không tồn tại không ảnh hưởng menu cấp nhà hàng. Phải có subscription effective tại Clock.instant() và grant `QR_MENU_VIEW` trong immutable feature snapshot. Không cần TABLE_MANAGEMENT, MENU_MANAGEMENT hoặc quyền đặt món. `endAt <= now` bị chặn dù stored status còn ACTIVE. Đọc không reconcile subscription hoặc thay snapshot; package catalog thay đổi không tự cập nhật grant cũ.

Response thành công và controller-handled errors đều có `Cache-Control: no-store`.

### GET /api/v1/public/menu/restaurants/{menuToken}

Response 200:

```json
{
  "restaurant": { "name": "Nhà hàng Demo", "currencyCode": "VND" },
  "groups": [
    { "id": "11111111-1111-4111-8111-111111111111", "name": "Món chính", "displayOrder": 0 }
  ]
}
```

Không có table/session hoặc restaurant UUID. Nhóm cùng nhà hàng, active, undeleted; cho phép nhóm rỗng; thứ tự `displayOrder ASC, name ASC, id ASC`.

### GET /api/v1/public/menu/restaurants/{menuToken}/items

| Query | Default | Rule |
| --- | --- | --- |
| q | absent | <=100 ký tự, trim, case-insensitive, chỉ tìm tên. `%`, `_`, `\` là ký tự thường. |
| groupId | absent | UUID nhóm visible cùng restaurant. UUID sai định dạng: 400; foreign/missing/hidden: 404. |
| page | 0 | Integer >=0. |
| size | 20 | Integer 1–100. |
| sortBy | name | name hoặc salePrice. |
| direction | asc | asc hoặc desc, case-insensitive. |

Sort luôn thêm `id ASC` làm tie-breaker. Visibility/filter/count thực hiện trong SQL trước pagination; group của món được load bằng batch query.

Response 200:

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

group/description/imageUrl có thể null. Chỉ MENU_ITEM active, undeleted, AVAILABLE/OUT_OF_STOCK được hiển thị; OUT_OF_STOCK vẫn có mặt để gắn nhãn hết hàng. group=null được phép; group không null phải cùng tenant, active, undeleted. Không có enum DISABLED trong model hiện tại: món bị disable là active=false.

Không trả costPrice, SKU, trackInventory, metadata, recipe, user/staff, snapshot, token, tenant UUID, version/timestamp, table/session. Empty result: `content: []`, totalElements/totalPages=0; page vượt cuối trả empty content, không phải 404.

## Quản lý link authenticated

Headers:

```http
Authorization: Bearer <accessToken>
Accept: application/json
```

Cả ba endpoint yêu cầu `RESTAURANT_PROFILE_UPDATE`, tenant lấy từ principal. Quyền mặc định ở OWNER và MANAGER; người được cấp quyền cũng có thể dùng. System account không có tenant bị từ chối. Token không xuất hiện trong DTO profile/admin list/login. Không cần QR_MENU_VIEW hoặc subscription chỉ để quản lý link; trạng thái account/restaurant và các security filter private vẫn áp dụng. Khách mở link sau đó vẫn phải qua public feature gate.

Response 200 chung:

```json
{
  "menuToken": "<token cấp nhà hàng>",
  "menuPath": "/menu/<token cấp nhà hàng>"
}
```

Giá trị trong dấu `<...>` là placeholder. Backend chỉ trả path frontend, không hardcode domain/localhost. Response dùng no-store.

### GET /api/v1/restaurants/me/menu-link

Không body; chỉ đọc. Token null trả 404 `PUBLIC_MENU_LINK_NOT_INITIALIZED`, không tạo token/audit.

### POST /api/v1/restaurants/me/menu-link

Không body; response 200. Lock restaurant pessimistic write. Null token: sinh/lưu, ghi `RESTAURANT_MENU_LINK_INITIALIZED`. Token đã có: trả nguyên token, không đổi/audit. Hai request initialize đồng thời trả cùng link, một audit. Không nhận token tự chọn hay restaurantId từ request.

### POST /api/v1/restaurants/me/menu-link/rotate

Headers thêm `Content-Type: application/json`.

```json
{ "expectedToken": "<token hiện tại từ GET>" }
```

expectedToken bắt buộc, không blank, tối đa 128 ký tự để so sánh token lưu hiện tại; field không hỗ trợ bị từ chối. Không dùng expectedVersion vì Restaurant không có @Version. Dưới cùng pessimistic lock, expectedToken cũ trả 409 `CONCURRENT_MENU_LINK_UPDATE`; token chưa khởi tạo trả 404 `PUBLIC_MENU_LINK_NOT_INITIALIZED`. Token mới phải khác token cũ; unique collision trả 409 `PUBLIC_MENU_TOKEN_CONFLICT`.

Lưu token và audit `RESTAURANT_MENU_LINK_ROTATED` trong cùng transaction. Audit failure rollback token. Hai rotate cùng expectedToken chỉ một thành công. Không đổi QR bàn/snapshot. Token cũ bị từ chối từ request public tiếp theo, không xóa được nội dung đã tải trong browser.

## Errors

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | Query hoặc expectedToken không hợp lệ. |
| 400 | INVALID_REQUEST_BODY | Body thiếu/malformed hoặc field không hỗ trợ. |
| 403 | PUBLIC_MENU_UNAVAILABLE | Không có effective subscription hoặc QR_MENU_VIEW snapshot grant. |
| 404 | PUBLIC_MENU_NOT_FOUND | Token invalid/unknown/rotated hoặc restaurant unavailable. |
| 404 | PUBLIC_MENU_GROUP_NOT_FOUND | Group filter foreign/missing/hidden. |
| 404 | PUBLIC_MENU_LINK_NOT_INITIALIZED | Link chưa có token, cần POST initialize. |
| 404 | RESTAURANT_NOT_FOUND | Private tenant restaurant không tồn tại/đã xóa. |
| 403 | FORBIDDEN / TENANT_ACCESS_DENIED | Thiếu permission hoặc tenant context. |
| 409 | CONCURRENT_MENU_LINK_UPDATE | expectedToken cũ hoặc locking conflict. |
| 409 | PUBLIC_MENU_TOKEN_CONFLICT | Unique collision hoặc không tạo được token mới. |
| 500 | INTERNAL_ERROR | Lỗi không dự kiến; không giả thành lỗi entitlement. |

Lỗi do controller mới xử lý có schema:

```json
{
  "success": false,
  "code": "PUBLIC_MENU_NOT_FOUND",
  "message": "Menu không còn khả dụng.",
  "details": {},
  "timestamp": "2026-10-07T00:00:00Z"
}
```

Auth/account/restaurant security filters chạy trước controller giữ schema lỗi security hiện có (`fieldErrors`), ví dụ 401/ACCOUNT_INACTIVE và 403/RESTAURANT_INACTIVE. Contract lỗi QR bàn cũ cũng được giữ nguyên. Frontend xử lý bằng status/code, không so khớp message. Lỗi không echo token, rejected values, URL hoặc SQL.

## Postman

Environment: `baseUrl=http://localhost:8080`, accessToken, menuToken.

1. GET `{{baseUrl}}/api/v1/restaurants/me/menu-link` với Bearer token.
2. Nếu chưa khởi tạo, POST cùng URL với Bearer token, không body; lưu menuToken.
3. Chọn No Auth cho GET `{{baseUrl}}/api/v1/public/menu/restaurants/{{menuToken}}`.
4. GET `{{baseUrl}}/api/v1/public/menu/restaurants/{{menuToken}}/items?page=0&size=20&sortBy=salePrice&direction=asc`.
5. POST authenticated `/api/v1/restaurants/me/menu-link/rotate` với expectedToken hiện tại.
6. Token cũ trả PUBLIC_MENU_NOT_FOUND, token mới trả 200 nếu feature gate hợp lệ.

## Deployment và kiểm thử

CORS giữ nguyên allowed origins cấu hình. Không cache menu API tại service worker/CDN/application. Giữ request logging và JDBC bind/extract tracing tắt; mask `/api/v1/public/menu/restaurants/{redacted}` trong proxy/CDN/APM/access logs, không ghi raw request/response body của menu-link/rotate hoặc dùng token làm metric label. Website đặt Referrer-Policy no-referrer. DTO request/response redact toString; audit mutation chỉ có initialized/rotated metadata, không chứa token/path.

Các test PublicRestaurantMenuPolicyTest/IntegrationTest/OpenApiTest bao phủ eligibility, snapshot, visibility/paging, public allowlist, security, concurrency, audit rollback, unique constraint, redaction và OpenAPI. Integration tạo/dọn schema riêng it_restaurant_menu_*. Không cleanup dev/public và không tắt audit append-only trigger.
