# Purchase và onboarding — Giai đoạn 2

Năm API dưới đây đã được hiện thực ở backend. QR, payment attempt, webhook, settlement, activation từ purchase và admin payment review chưa triển khai. [OpenAPI](openapi/subscription-purchase-api.yaml) đánh dấu IMPLEMENTED/PLANNED theo từng operation; [thiết kế](subscription-purchase-design.md), [FE sync](subscription-purchase-fe-sync.md), [test plan](subscription-purchase-test-plan.md).

Base URL: `http://localhost:8080/api/v1`.

## Chuẩn bị Postman

1. Chạy migration mới qua startup/Flyway theo cấu hình dự án; không clean dữ liệu phát triển.
2. OWNER đăng nhập hoặc refresh token để nhận permission mới. Token cũ không tự có thêm quyền.
3. Mặc định `subscription-purchase.accept-new=false`; để kiểm thử local, đặt biến môi trường cho tiến trình backend:

```powershell
$env:SUBSCRIPTION_PURCHASE_ACCEPT_NEW = 'true'
.\mvnw.cmd spring-boot:run
```

Dùng JDK hỗ trợ Java21. Flag chỉ mở tạo purchase mới; chưa mở khả năng thanh toán. Không bật CTA mua/thanh toán production khi Phase3–5 chưa sẵn sàng.

4. `GET /packages` để chọn package active có giá VND nguyên đồng, lớn hơn0. Ví dụ dưới dùng catalog minh họa PRO399000VND; dữ liệu thực tế phụ thuộc catalog của bạn.

Postman variables: `baseUrl=http://localhost:8080/api/v1`, `accessToken`, `packageCode`, `purchaseCreateKey`, `purchaseId`, `purchaseVersion`.

Protected headers:

```http
Authorization: Bearer {{accessToken}}
Accept: application/json
```

POST thêm `Content-Type: application/json`. Tất cả response mới có `Cache-Control: no-store`; `Vary` chứa Authorization, có thể kèm các header CORS khác. Tenant và actor lấy từ principal, không truyền restaurantId/actorId.

## 1. GET /subscriptions/onboarding

Quyền: **OWNER + SUBSCRIPTION_PURCHASE_VIEW**. Không yêu cầu subscription hoặc feature active. Không body/query.

Ví dụ OWNER mới, flag nhận mới đang bật:

```json
{
  "subscriptionState": "NONE",
  "currentSubscription": null,
  "currentPurchase": null,
  "unresolvedPaymentReviewCount": 0,
  "canPurchase": true,
  "blockedReason": null,
  "allowedActions": ["CREATE_PURCHASE"],
  "serverTime": "2026-11-01T03:00:00Z"
}
```

HTTP200 kể cả chưa mua gói. subscriptionState là NONE/PENDING/ACTIVE/EXPIRED/CANCELLED. currentSubscription giữ stored status và có effective; stored ACTIVE đã đến endAt có thể được project subscriptionState EXPIRED mà DB chưa thay đổi. Future ACTIVE vẫn chặn mua.

currentPurchase là purchase mở, có thể stored PENDING_PAYMENT nhưng displayStatus EXPIRED. canPurchase cho biết khả năng tạo ý định mới, backend vẫn kiểm tra lại dưới lock. unresolvedPaymentReviewCount là số event review thực tế, độc lập subscription ACTIVE.

blockedReason ưu tiên: PURCHASE_UNAVAILABLE → PURCHASE_PERMISSION_REQUIRED → SUBSCRIPTION_ALREADY_ACTIVE → SUBSCRIPTION_PENDING_EXISTS → PURCHASE_REVIEW_REQUIRED → PURCHASE_IN_PROGRESS. Khi flag mặc định tắt, response200 với canPurchase=false, blockedReason=PURCHASE_UNAVAILABLE.

GET không ghi trạng thái hoặc audit. Phase2 không trả CREATE_PAYMENT_ATTEMPT như hành động đã có thể thực hiện.

## 2. POST /subscription-purchases

Quyền: **OWNER + SUBSCRIPTION_PURCHASE_CREATE**.

```http
POST {{baseUrl}}/subscription-purchases
Authorization: Bearer {{accessToken}}
Content-Type: application/json
Idempotency-Key: {{purchaseCreateKey}}
```

```json
{"packageCode":"PRO"}
```

Key8..100 ký tự, chỉ `[A-Za-z0-9._:-]`. Sinh key một lần cho một ý định, ví dụ `purchase-20261101-000001`; giữ nguyên khi retry timeout. PackageCode trim + uppercase trước lookup/fingerprint. Không gửi giá/feature/maxStaff/tenant/actor/paid/status/startAt/endAt; field lạ trả400 INVALID_REQUEST_BODY.

Lần đầu201, `Idempotency-Replayed:false`. Ví dụ PurchaseView:

```json
{
  "id":"11111111-1111-4111-8111-111111111111",
  "status":"PENDING_PAYMENT",
  "displayStatus":"PENDING_PAYMENT",
  "quote":{
    "schemaVersion":1,
    "packageId":"66666666-6666-4666-8666-666666666666",
    "packageCode":"PRO",
    "packageName":"Pro",
    "priceAmount":399000,
    "currencyCode":"VND",
    "billingCycleMonths":1,
    "features":[{"code":"MENU_MANAGEMENT","limits":{}},{"code":"RECIPE_MANAGEMENT","limits":{}}],
    "maxStaff":30,
    "quotedAt":"2026-11-01T03:00:00Z",
    "expiresAt":"2026-11-01T03:15:00Z",
    "billingZoneId":"Asia/Ho_Chi_Minh"
  },
  "subscriptionId":null,
  "paidAt":null,
  "activatedAt":null,
  "cancelledAt":null,
  "closedAt":null,
  "reviewReason":null,
  "allowedActions":["VIEW_PURCHASE","CANCEL_PURCHASE"],
  "createdAt":"2026-11-01T03:00:00Z",
  "updatedAt":"2026-11-01T03:00:00Z",
  "version":0,
  "serverTime":"2026-11-01T03:00:00Z"
}
```

features trả đầy đủ snapshot thực tế, không chỉ hai feature minh họa. maxStaff=null vẫn có mặt và nghĩa là không giới hạn. Quote có hạn15phút, khoảng `[quotedAt,expiresAt)`; expiresAt==now là hết hạn. Giá và grants không đổi khi admin sửa catalog hoặc disable package.

Postman Tests có thể lưu resource:

```javascript
const purchase = pm.response.json();
if ([200, 201].includes(pm.response.code)) {
  pm.environment.set('purchaseId', purchase.id);
  pm.environment.set('purchaseVersion', purchase.version);
}
```

Replay cùng key/canonical package trả200, `Idempotency-Replayed:true`, cùng **body lúc tạo** đã lưu. Body replay có thể cũ, phải GET detail để lấy trạng thái/version hiện tại. Replay vẫn được xử lý trước eligibility/flag mới, nhưng sau access guard. Cùng key khác package409 IDEMPOTENCY_KEY_REUSED.

Tạo purchase không tạo subscription PENDING, không cấp entitlement, không đánh dấu trả tiền. Active subscription/PENDING admin hoặc open purchase chặn ý định mới. Purchase hết hạn được command reconcile trước khi tạo mới.

## 3. GET /subscription-purchases

Quyền: **OWNER + SUBSCRIPTION_PURCHASE_VIEW**.

```http
GET {{baseUrl}}/subscription-purchases?status=CANCELLED&page=0&size=20&createdFrom=2026-11-01T00:00:00Z&createdTo=2026-11-02T00:00:00Z
```

Query đều optional: status, createdFrom inclusive, createdTo exclusive, page0, size20 (1..100). Timestamp phải UTC kết thúc Z. from<to; nếu đủ hai đầu, khoảng tối đa366ngày. Sort cố định createdAt DESC,id DESC. Không có restaurantId filter cho OWNER.

Response200 theo PageResponse hiện có:

```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

content chứa các PurchaseView như mục2, phản ánh trạng thái mới khi GET. status filter dùng stored status, không dùng displayStatus. List dùng quote lịch sử, không đọc lại catalog từng record. GET không reconcile/audit.

## 4. GET /subscription-purchases/{purchaseId}

Quyền: **OWNER + SUBSCRIPTION_PURCHASE_VIEW**.

```http
GET {{baseUrl}}/subscription-purchases/{{purchaseId}}
```

Response200 PurchaseView đầy đủ như mục2. Khác tenant hoặc không tồn tại:404 PURCHASE_NOT_FOUND.

Sau15phút, GET có thể trả `status=PENDING_PAYMENT`, `displayStatus=EXPIRED`, version vẫn0 vì chưa có command reconcile. GET không tự ghi EXPIRED. PAID đã lưu không bị project hết hạn chỉ vì quote đã qua hạn; những trạng thái tài chính này chỉ do giai đoạn sau ghi, Phase2 không có endpoint giả lập.

Không expose requestHash, idempotency storage, creationResponse hoặc raw provider event. Dùng API này để phục hồi sau replay/reload trước khi thao tác tiếp.

## 5. POST /subscription-purchases/{purchaseId}/cancel

Quyền: **OWNER + SUBSCRIPTION_PURCHASE_CANCEL**. Không yêu cầu Idempotency-Key.

```http
POST {{baseUrl}}/subscription-purchases/{{purchaseId}}/cancel
Authorization: Bearer {{accessToken}}
Content-Type: application/json
```

```json
{"expectedVersion":0,"reason":"Chọn nhầm gói"}
```

expectedVersion bắt buộc, integer>=0; reason optional, tối đa500ký tự, không nhận chuỗi rỗng/blank. Lấy version hiện tại bằng GET trước khi cancel. Response200 PurchaseView CANCELLED, cancelledAt được chốt, version tăng, quote giữ nguyên. Có thể tạo purchase mới bằng key mới sau khi slot được giải phóng.

Cancel lần hai200 no-op trước version check; không đổi reason/cancelledAt hoặc audit lần đầu. PAID/ACTIVATED/REVIEW_REQUIRED/CLOSED409 PURCHASE_CANNOT_CANCEL; EXPIRED hoặc pending quá hạn409 PURCHASE_PERIOD_EXPIRED; version stale409 CONCURRENT_SUBSCRIPTION_UPDATE. Nếu có pending attempts trong dữ liệu, chúng được CANCELLED cùng transaction, không đụng attempt SUCCESS/evidence.

## Lỗi và cách phục hồi

```json
{
  "success":false,
  "code":"PURCHASE_IN_PROGRESS",
  "message":"Purchase command conflicts with current state",
  "fieldErrors":{},
  "timestamp":"2026-11-01T03:00:00Z"
}
```

Không có wrapper success/data ở response thành công. Các message có thể khác; FE dùng code.

| HTTP/code | Hành động |
|---|---|
|401 UNAUTHORIZED|Login/refresh theo auth abstraction; retry mutation cùng key|
|401 ACCOUNT_INACTIVE|Thông báo tài khoản không hợp lệ; không tiếp tục replay|
|403 FORBIDDEN|Sai role hoặc thiếu/revoked permission; refresh token khi vừa được seed quyền|
|403 RESTAURANT_INACTIVE|Restaurant bị suspend/inactive; không bypass bằng token cũ|
|400 VALIDATION_ERROR / INVALID_REQUEST_BODY|Sửa header/query/body, không tự thêm key mới để né validation|
|404 PACKAGE_NOT_FOUND|Chọn package active từ catalog mới|
|404 PURCHASE_NOT_FOUND|Không dò tenant khác|
|409 SUBSCRIPTION_ALREADY_ACTIVE|MVP chưa renewal/change self-service; không tạo lại purchase|
|409 SUBSCRIPTION_PENDING_EXISTS|Admin activate/cancel subscription PENDING hiện có|
|409 PURCHASE_IN_PROGRESS / PURCHASE_REVIEW_REQUIRED|GET onboarding để tiếp tục/hỗ trợ purchase đang có|
|409 IDEMPOTENCY_KEY_REUSED|Đối chiếu key với ý định gốc; không thay key âm thầm|
|409 PURCHASE_PERIOD_EXPIRED|Ẩn ý định thanh toán cũ; GET onboarding trước purchase mới|
|409 PURCHASE_CANNOT_CANCEL|Không hủy trạng thái đã trả tiền/review như chưa trả|
|409 CONCURRENT_SUBSCRIPTION_UPDATE|GET lại trạng thái/version; retry cùng ý định/key khi phù hợp|
|422 PACKAGE_NOT_PURCHASABLE|Chỉ giá VND nguyên đồng dương, max999999999999|
|503 PURCHASE_UNAVAILABLE|Đang tắt nhận mới; read/replay/cancel vẫn hoạt động|

Không có rate limiter mới ở Phase2; không khẳng định API đang trả429 theo một quota chưa được hiện thực.

Admin create/activate/change-package giữ URL/body/security cũ, thêm409 SUBSCRIPTION_PURCHASE_IN_PROGRESS nếu restaurant có purchase mở. Guard vẫn chạy khi accept-new=false; stale pending quá hạn được reconcile trong command thành công. Admin cancel subscription giữ nghiệp vụ cũ.

## Kịch bản kiểm thử tay ngắn

1. Owner chưa có gói: GET onboarding200 → create201 → GET list/detail thấy cùng ID, không có subscription mới.
2. POST cùng key/body:200/replayed=true; đổi package nhưng giữ key:409 IDEMPOTENCY_KEY_REUSED.
3. POST key mới khi purchase còn hạn:409 PURCHASE_IN_PROGRESS.
4. Cancel version đúng:200; lặp cancel version cũ:200, audit chỉ một; tạo purchase mới/key mới:201.
5. Tắt nhận mới: GET vẫn200, replay vẫn200; ý định mới503 PURCHASE_UNAVAILABLE.
6. Đăng nhập OWNER khác: detail/cancel purchase cũ404; staff có quyền bị gán nhầm vẫn403.
7. Khi purchase mở, admin gán gói409 SUBSCRIPTION_PURCHASE_IN_PROGRESS.
8. Sau quote hết hạn: GET chỉ project EXPIRED; create mới hợp lệ hoặc admin gán gói sẽ reconcile under lock.

Không test Phase2 bằng chuyển tiền thật: backend chưa có QR/webhook/settlement/activation từ purchase.
