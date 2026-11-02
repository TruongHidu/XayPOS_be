# Web onboarding và mua gói — hướng dẫn đồng bộ FE

**Triển khai một phần:** năm API Phase2 đã có runtime; QR/attempt/webhook/activation/review Phase3–5 vẫn PLANNED. Ngày chốt2026-11-01. Tài liệu hướng dẫn web, chưa sửa web/Flutter trong Phase2. Đối chiếu [OpenAPI](openapi/subscription-purchase-api.yaml), [thiết kế](subscription-purchase-design.md), [test plan](subscription-purchase-test-plan.md) trước tích hợp.

## 1. Phân biệt API đang có với API dự kiến

Base URL local: `http://localhost:8080/api/v1`. Các path trong bảng đã bỏ base prefix.

| Trạng thái | Method/path | Quyền / thời điểm dùng |
|---|---|---|
| **EXISTING** | POST `/auth/register-restaurant` | Public; tạo owner+restaurant+token, không tạo subscription |
| **EXISTING** | POST `/auth/login`, POST `/auth/refresh`, GET `/auth/me` | Dùng auth abstraction đang có; giữ contract hiện hành |
| **EXISTING** | GET `/packages`, GET `/packages/{packageCode}` | Public catalog active; giá ở đây chưa phải quote |
| **EXISTING** | GET `/restaurants/me` | Bearer + RESTAURANT_PROFILE_VIEW; không cần gói active |
| **EXISTING** | GET `/me/entitlements` | Bearer; chưa có gói trả403 SUBSCRIPTION_NOT_ACTIVE, không phải token hết hạn |
| IMPLEMENTED Phase2 | GET `/subscriptions/onboarding` | OWNER + SUBSCRIPTION_PURCHASE_VIEW |
| IMPLEMENTED Phase2 | POST `/subscription-purchases` | OWNER + SUBSCRIPTION_PURCHASE_CREATE |
| IMPLEMENTED Phase2 | GET `/subscription-purchases` | OWNER + SUBSCRIPTION_PURCHASE_VIEW |
| IMPLEMENTED Phase2 | GET `/subscription-purchases/{purchaseId}` | OWNER + SUBSCRIPTION_PURCHASE_VIEW |
| PLANNED Phase3 | POST `/subscription-purchases/{purchaseId}/payment-attempts` | OWNER + SUBSCRIPTION_PURCHASE_CREATE |
| PLANNED Phase3 | GET `/subscription-purchases/{purchaseId}/payment-attempts/current` | OWNER + SUBSCRIPTION_PURCHASE_VIEW |
| IMPLEMENTED Phase2 | POST `/subscription-purchases/{purchaseId}/cancel` | OWNER + SUBSCRIPTION_PURCHASE_CANCEL |
| PLANNED Phase4 | POST `/webhooks/subscriptions/sepay` | **Provider HMAC**, không gọi từ browser |
| PLANNED Phase5 | GET `/admin/subscription-payment-events` | System SUPER_ADMIN + SUBSCRIPTION_PAYMENT_REVIEW |
| PLANNED Phase5 | POST `/admin/subscription-payment-events/{eventId}/reconcile` | System SUPER_ADMIN + SUBSCRIPTION_PAYMENT_REVIEW |

OWNER phải đúng tenant; không dùng restaurantId từ route/query để quyết định tenant. Staff không được gọi dù có SUBSCRIPTION_VIEW. Các mutation admin cũ vẫn yêu cầu SUBSCRIPTION_MANAGE và không dành cho OWNER. API mới không yêu cầu feature/gói đang active. Account khóa và restaurant suspend vẫn bị chặn.

Không bật CTA mua gói production chỉ vì các endpoint Phase2 đã có: Phase3–5 và worker/webhook/recovery phải nghiệm thu trước. Phần Phase2 có thể tích hợp/test theo [API/Postman](subscription-purchase-api.md); phần PLANNED dùng để thiết kế/types/fixtures; **không mock thông báo thanh toán thành công như runtime đã tích hợp**.

## 2. Phần có thể sử dụng ngay ở Phase2

Đăng ký/login → GET onboarding → POST purchase → GET list/detail → cancel hoặc tiếp tục xem purchase. Default accept-new=false; local/test có thể bật SUBSCRIPTION_PURCHASE_ACCEPT_NEW=true. Không render QR hoặc gọi payment-attempt/current khi endpoints còn PLANNED. Phase2 purchase pending chỉ trả VIEW_PURCHASE/CANCEL_PURCHASE theo quyền, không CREATE_PAYMENT_ATTEMPT. Replay trả response cũ, luôn GET lại trước thao tác.

## 3. Trình tự đích sau khi Phase3–5 hoàn thiện

1. Trang giới thiệu lấy GET packages, cho chọn packageCode dự kiến. Có thể giữ riêng code này trong state/session; không giữ giá như bằng chứng giao dịch.
2. Đăng ký OWNER+restaurant qua API hiện có. Dùng auth repository/session manager/interceptor của dự án để nhận và lưu phiên. Không lưu mật khẩu localStorage, không tự xây token store thứ hai, không đưa token vào URL/QR.
3. Sau login/registration gọi GET onboarding. NONE/EXPIRED/CANCELLED có thể mua nếu canPurchase=true. ACTIVE mở app theo entitlement; PENDING subscription của admin thông báo chờ admin activate/cancel. Không đặt restaurant INACTIVE chỉ vì chưa mua.
4. User xác nhận package → sinh idempotency key cho **một ý định** → POST purchase. Hiển thị giá/kỳ/features/maxStaff/expiresAt từ quote response mới. Không lấy giá cũ catalog để tạo QR.
5. Đọc GET purchase hiện tại; nếu còn PENDING_PAYMENT, GET current attempt. Nếu chưa có attempt và user chọn thanh toán → tạo attempt bằng key riêng. Không tự POST khi mount/reload page.
6. Render VietQR offline từ instructions.qrPayload, accountName/accountNumber/bankCode, amount và transferContent server trả. Mẫu OpenAPI là dữ liệu DEMO không phải QR có thể thanh toán. Không tự tính/đổi transferContent hoặc bank account. Không render QR khi displayStatus không PENDING hoặc purchase đã PAID/review/terminal.
7. Poll GET purchase mỗi3–5giây, backoff tối đa30giây nếu mạng lỗi, pause khi tab hidden và refresh khi foreground. Khi attempt state cần cập nhật thì GET current. MVP không yêu cầu WebSocket. Không có nút nào ở FE tự set paid/activated; “Tôi đã chuyển tiền” chỉ gọi GET/refetch.
8. PAID: ẩn QR, hiển thị “Đã nhận tiền, đang kích hoạt”; tiếp tục polling, không tạo attempt/purchase khác. ACTIVATED: GET entitlement, invalidate cache features/maxStaff, refresh auth permission theo abstraction nếu cần, mở chức năng.
9. EXPIRED/CANCELLED: dừng lời mời chuyển khoản; có thể cho tạo ý định mới sau refetch onboarding. REVIEW_REQUIRED/CLOSED: hiện thông báo liên hệ hỗ trợ và ID, không gọi lại payment. Money received vẫn giữ paidAt ngay cả khi review/closed.
10. Login lại/đổi thiết bị: GET onboarding → currentPurchase nếu có → GET detail/current attempt; nếu không có currentPurchase, lấy lịch sử page0 để xem purchase terminal. Không phục hồi bằng cách tạo lại một payment mới.

## 4. Đăng ký hiện tại: ví dụ đúng DTO

```http
POST /api/v1/auth/register-restaurant
Content-Type: application/json
```

```json
{
  "restaurantCode": "QUAN_DEMO_20261101",
  "restaurantName": "Nhà hàng Demo",
  "timezone": "Asia/Ho_Chi_Minh",
  "currencyCode": "VND",
  "ownerName": "Nguyễn Văn A",
  "ownerEmail": "owner-demo@example.com",
  "password": "ExampleOnly-ChangeMe123"
}
```

Optional: legalName<=200, phone<=30, address<=500, ownerPhone<=30. restaurantCode2..50, restaurantName/ownerName<=150, ownerEmail<=254 email hợp lệ, password8..100. Không gửi ownerFullName thay ownerName. Token response hiện có: accessToken, refreshToken, tokenType, expiresIn, user (id/restaurantId/restaurantCode/restaurantName/name/email/phone/role/permissions); giữ serializer/null convention hiện hành. Không lưu password sau request. Đăng ký chưa có idempotency contract mới; timeout đăng ký phải kiểm tra/login lại, không tự dùng lại cơ chế purchase cho registration.

## 5. Request/response mới quan trọng (PLANNED)

Protected request:

```http
Authorization: Bearer <access-token>
Accept: application/json
```

Create purchase:

```http
POST /api/v1/subscription-purchases
Content-Type: application/json
Idempotency-Key: purchase-demo-20261101-0001
```

```json
{"packageCode":"PRO"}
```

Chỉ gửi packageCode. **Không** gửi restaurantId/actorId/amount/currency/featureSnapshot/maxStaff/paid/status/startAt/endAt; unknown fields400. Response201 lần đầu,200 replay, header Idempotency-Replayed false/true. Resource PurchaseView không có success/data wrapper. Quote chứa schemaVersion1/packageId/code/name/priceAmount/currencyCode/billingCycleMonths/features/maxStaff/quotedAt/expiresAt/billingZoneId.

Create attempt:

```http
POST /api/v1/subscription-purchases/11111111-1111-4111-8111-111111111111/payment-attempts
Content-Type: application/json
Idempotency-Key: attempt-demo-20261101-0001
```

```json
{}
```

Response201/200 PaymentAttemptView có id/purchaseId/status/displayStatus/provider/referenceCode/amount/currencyCode/expiresAt/instructions/paidAt/version/serverTime. Không gọi endpoint order payments để mua gói. Key mới khi có attempt PENDING trả409 PAYMENT_ATTEMPT_PENDING → GET current; không lặp POST key mới.

GET current attempt trả `{ "paymentAttempt": null, "serverTime": "2026-11-01T03:00:00Z" }` nếu chưa có. Nếu có, trả **attempt mới nhất kể cả terminal**, không chỉ PENDING. Purchase không tồn tại/khác tenant404. Các response đầy đủ cho pending/paid/activated/expiry/review ở examples OpenAPI; dùng spec làm nguồn types duy nhất.

Cancel:

```http
POST /api/v1/subscription-purchases/11111111-1111-4111-8111-111111111111/cancel
Content-Type: application/json
```

```json
{"expectedVersion":0,"reason":"Chọn nhầm gói"}
```

Response200 PurchaseView CANCELLED. Lần hai200/no audit trùng dù version cũ; PAID/review/activated/closed409 PURCHASE_CANNOT_CANCEL; EXPIRED409 PURCHASE_PERIOD_EXPIRED. Nếu cancel timeout: GET lại trước quyết định tiếp, không mặc định chưa nhận tiền.

## 6. Map UI theo trạng thái

| Dữ liệu | UI / hành động |
|---|---|
| subscriptionState=NONE, canPurchase=true | Chọn gói, CREATE_PURCHASE |
| subscriptionState=ACTIVE | Mở chức năng theo GET entitlement; không mời renewal trong MVP |
| subscriptionState=PENDING | Admin đã gán PENDING; liên hệ admin activate/cancel |
| subscriptionState=EXPIRED/CANCELLED | Được mua lại nếu canPurchase và allowedActions cho phép |
| purchase PENDING_PAYMENT/displayStatus PENDING_PAYMENT, chưa có attempt | CREATE_PAYMENT_ATTEMPT hoặc cancel nếu có quyền |
| purchase PENDING_PAYMENT, attempt PENDING/displayStatus PENDING | Hiển thị QR và countdown; WAIT_PAYMENT |
| purchase PENDING_PAYMENT/displayStatus EXPIRED | Hết quote theo server projection; không trả tiền nữa; refetch onboarding trước ý định mới |
| purchase PAID | WAIT_ACTIVATION; ẩn QR; không thanh toán lại, dù quote giờ đã hết hạn |
| purchase ACTIVATED | GET entitlement, invalidate cache, OPEN_APP |
| purchase CANCELLED/EXPIRED | Có thể tạo purchase mới sau eligibility mới; không tái sử dụng reference cũ |
| purchase REVIEW_REQUIRED | CONTACT_SUPPORT; paidAt có thể non-null hoặc null, không diễn giải là mất tiền |
| purchase CLOSED | Đã xử lý ngoài hệ thống; không mặc định refunded/activated |
| attempt FAILED | Nếu purchase còn hạn, user có thể chủ động tạo attempt khác bằng key mới; refetch trước |
| attempt SUCCESS | Đã match tiền, đợi purchase PAID/ACTIVATED, không chuyển lại tiền |
| unresolvedPaymentReviewCount>0 và subscription ACTIVE | Hiển thị badge hỗ trợ riêng, không khóa quyền lợi đang có |
| flag nhận mới tắt | Ẩn CTA mua mới; vẫn đọc, cancel hợp lệ, chờ kích hoạt và hỗ trợ purchase cũ |

Onboarding example:

```json
{
  "subscriptionState":"NONE",
  "currentSubscription":null,
  "currentPurchase":null,
  "unresolvedPaymentReviewCount":0,
  "canPurchase":true,
  "blockedReason":null,
  "allowedActions":["CREATE_PURCHASE"],
  "serverTime":"2026-11-01T03:00:00Z"
}
```

BlockedReason ưu tiên được BE chốt: PURCHASE_UNAVAILABLE → PURCHASE_PERMISSION_REQUIRED → SUBSCRIPTION_ALREADY_ACTIVE → SUBSCRIPTION_PENDING_EXISTS → PURCHASE_REVIEW_REQUIRED → PURCHASE_IN_PROGRESS. allowedActions thuộc enum CREATE_PURCHASE/VIEW_PURCHASE/CREATE_PAYMENT_ATTEMPT/CANCEL_PURCHASE/WAIT_PAYMENT/WAIT_ACTIVATION/CONTACT_SUPPORT/OPEN_APP. Không suy quyền chỉ từ role, số tiền hoặc một enum UI.

## 7. Retry, reload, thời gian, lỗi và bảo mật dữ liệu

- Lưu idempotency key với ý định và purchaseId trong state/storage thích hợp theo tenant+user; đây không phải secret. Cùng ý định sau timeout giữ key cũ và canonical body/path cũ; đổi package là ý định mới nhưng phải giải quyết purchase mở trước. Logout/đổi tenant phải xóa cache cross-tenant.
- Replay200 trả **body lúc tạo ban đầu**, có thể PENDING dù giờ ACTIVATED; ngay sau replay luôn GET detail/current. Chặn double tap hỗ trợ UX, DB vẫn quyết định concurrency.
- Countdown dùng `expiresAt - serverTime` tại response cộng elapsed monotonic; resync khi foreground. Không dùng đồng hồ thiết bị để chốt payment nghiệp vụ. Không tự “gia hạn QR” bằng cách cộng15phút ở FE.
- Price JSON integer VND, không tự nhân số tháng, không thêm phí/coupon; money<=999999999999 nên nằm trong vùng safe integer JS. format VND chỉ là presentation. maxStaff=null hiển thị “Không giới hạn”, không biến thành0.
- Mọi response mới Cache-Control:no-store; không service-worker cache/payment response, không log token/QR payload/account đủ ra analytics. Không secret SePay trong bundle; browser không gọi webhook/reconcile provider.
- 401 UNAUTHORIZED: refresh qua abstraction auth, retry cùng key một lần theo interceptor;401 ACCOUNT_INACTIVE: kết thúc phiên/thông báo account.403 SUBSCRIPTION_NOT_ACTIVE: điều hướng onboarding, **không logout/refresh vòng lặp**.403 FORBIDDEN: màn không đủ quyền;403 RESTAURANT_INACTIVE: bị suspend, không vòng tạo purchase.
- 409 IDEMPOTENCY_KEY_REUSED: lỗi key/body, không đổi key âm thầm để né; refetch/recover.409 PURCHASE_IN_PROGRESS/PAYMENT_ATTEMPT_PENDING: mở resource hiện tại.409 CONCURRENT_SUBSCRIPTION_UPDATE: GET version mới, không ghi đè bằng version cũ.
- 409 SUBSCRIPTION_ALREADY_ACTIVE/SUBSCRIPTION_PENDING_EXISTS: GET onboarding; không create+activate admin từ web OWNER.409 PURCHASE_PERIOD_EXPIRED: ẩn QR/refetch, ý định mới cần key mới.409 PURCHASE_REVIEW_REQUIRED/PURCHASE_CANNOT_CANCEL: hỗ trợ/chờ xử lý, không thu lại.
- 404 PURCHASE_NOT_FOUND: không cố dò tenant ID khác.422 PACKAGE_NOT_PURCHASABLE: chọn gói hợp lệ từ catalog mới.429 RATE_LIMITED: Retry-After giây;503 SERVICE_UNAVAILABLE: kết quả mutation có thể chưa rõ, retry key cũ.503 PURCHASE_UNAVAILABLE: ngừng nhận mới, read/replay vẫn có.503 PAYMENT_PROVIDER_UNAVAILABLE: giữ purchase, thử lại cùng ý định khi user chọn tiếp tục.
- List PageResponse content/page/size/totalElements/totalPages; page0,size20,max100; dates UTC createdFrom inclusive/createdTo exclusive. Refresh page0 sau mutation, không cộng list thủ công dẫn trùng. status filter là stored status, không filter displayStatus client như state DB.

## 8. Admin review UI (Phase5/6, tách OWNER)

GET admin events filter status=REVIEW_REQUIRED; unmatched fields có thể null. RECHECK body `{action:"RECHECK",expectedVersion,reason}` →202 RETRY_PENDING, UI hiển thị đã queue, **không báo đã kích hoạt**. CLOSE_OFFLINE thêm resolutionReference bắt buộc →200 CLOSED, giữ audit/bằng chứng xử lý ngoài hệ thống, không có API refund tự động. Reason>=10ký tự, resolutionReference>=3ký tự.

Sau timeout refetch GET collection filter purchaseId/status để xác định event/version, không lặp mutation với version cũ; stale409 không audit trùng. Admin DTO không trả raw payload/secret, không cần yêu cầu BE mở dữ liệu nhạy cảm cho UI. Purchase ACTIVATED và event tiền thừa CLOSED vẫn độc lập.

## 9. Phạm vi hiện tại

Năm API Phase2 có thể test bằng Postman theo tài liệu API. Các API Phase3–5 vẫn chưa triển khai; chưa thể chuyển khoản mua gói hoặc kích hoạt tự động. Phase2 không sửa web/Flutter và không thêm public/provider security bypass. Acceptance E2E, replay/reload/multi-device và quyền được liệt kê trong test plan; các case web E2E/provider tương lai vẫn **chưa chạy**.
