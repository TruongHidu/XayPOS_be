# Mua gói POS SaaS — thiết kế và triển khai Giai đoạn 2

Ngày đối chiếu: 2026-11-01. **Phase2 IMPLEMENTED:** năm API purchase/onboarding và schema/permission V41. **Phase3 IMPLEMENTED (2026-11-02):** payment attempt, mã chuyển khoản, QR SePay/VietQR offline, V42 và job expire purchase, xem [Phase3](subscription-purchase-phase3-implementation.md). **Phase4 IMPLEMENTED (2026-11-02):** webhook SePay HMAC, inbox, worker đối chiếu PAID và kích hoạt gói qua port `PurchasedSubscriptionActivation`, xem [Phase4](subscription-purchase-phase4-implementation.md). **Phase5 PLANNED:** đối soát API SePay và API admin review. Mục1 ghi lại baseline trước Phase2; mục12 mô tả implementation. Contract máy đọc: [OpenAPI](openapi/subscription-purchase-api.yaml); [test plan](subscription-purchase-test-plan.md); [FE sync](subscription-purchase-fe-sync.md).

## 1. Baseline trước Giai đoạn 2 đã xác minh

Repository: `D:\Đồ án tốt nghiệp\Code\pos-saas-be`. Đã đọc các controller/service/repository/DTO/domain auth, restaurant, subscription, payment, security, audit, Clock; migration và test liên quan. Không có AGENTS.md trong repository hoặc các thư mục cha được kiểm tra.

| Thành phần hiện tại | Kết quả đối chiếu mã nguồn |
|---|---|
| `AuthController`, `RestaurantRegistrationService`, `RegisterRestaurantRequest` | `POST /api/v1/auth/register-restaurant` tạo restaurant ACTIVE + user OWNER + token trong một transaction; mã hóa mật khẩu; **không tạo subscription**. DTO dùng `ownerName`, có `timezone`, `currencyCode`; ví dụ đăng ký cũ trong README chưa phản ánh đủ DTO này. |
| `AuthenticationService`, `AuthTokenService`, `RefreshTokenService` | Login/refresh kiểm tra account và restaurant, không yêu cầu subscription. Refresh token lưu hash, có rotation/revoke. Giữ contract AuthResponse hiện có. |
| `RestaurantProfileController` | `GET/PATCH /api/v1/restaurants/me`: `RESTAURANT_PROFILE_VIEW/UPDATE`; lấy tenant từ principal, query hồ sơ không yêu cầu feature. |
| `PackageController`, `PackagePlanRepository`, `PackageFeatureCatalog` | `GET /api/v1/packages`, `GET /api/v1/packages/{packageCode}` đã public; chỉ trả catalog active. Chưa có quote mua gói. |
| `SubscriptionController`, `EntitlementService`, `SubscriptionValidityPolicy` | Current/history yêu cầu `SUBSCRIPTION_VIEW`; `GET /api/v1/me/entitlements` yêu cầu bearer. Không có effective subscription trả `403 SUBSCRIPTION_NOT_ACTIVE`. Effective = ACTIVE và `startAt <= now < endAt`. GET không reconcile/audit. |
| `AdminSubscriptionController`, `SubscriptionCommandService` | Create/activate/change-package/cancel đã có, yêu cầu system SUPER_ADMIN + `SUBSCRIPTION_MANAGE`; lock restaurant trước kiểm tra. Admin create sinh PENDING. Không có owner tự mua. |
| `FeatureSnapshotFactory`, `SubscriptionFeatureSnapshot` | Admin activate hiện đọc catalog live rồi chốt snapshot schemaVersion=2, top-level `maxStaff`; null là unlimited. Snapshot cũ giữ nguyên. Purchase activation tương lai cần đường cấp từ quote, không gọi lại factory live một cách mù quáng. |
| `PackageAdminService` | Sửa metadata/mapping đều lock package pessimistic. `PackagePlan` chưa có @Version; không được dựa vào version không tồn tại. Feature active là dữ liệu riêng, package lock một mình chưa bảo đảm snapshot đọc nhiều query nhất quán. |
| `SubscriptionExpirationService` | `expireIfDue` MANDATORY dùng Clock; `reconcileByIdIfDue` REQUIRES_NEW; scheduled batch lock subscription SKIP LOCKED. Batch hiện tại không lock restaurant trước. Không mô tả sai rằng tất cả job đã theo restaurant-first. |
| `RestaurantRepository` | `findByIdForUpdate` pessimistic write, bỏ restaurant soft-delete. |
| `PaymentController`, `PaymentSettlementService`, entity/policy payment | Thu tiền **order món ăn**, có orderId/order settlement, CASH/TRANSFER thủ công; convention 201 mới/200 replay, `Idempotency-Replayed`, `Cache-Control: no-store`. Không dùng service này để thu tiền SaaS. |
| `SecurityConfig`, user/tenant filters | JWT + kiểm tra account active (`401 ACCOUNT_INACTIVE`), restaurant ACTIVE (`403 RESTAURANT_INACTIVE` cho protected tenant request). Webhook mới chưa có matcher/xác thực provider. |
| `ErrorResponse`, `GlobalExceptionHandler`, `PageResponse`, `AuditService`, `TimeConfig` | Error `{success:false,code,message,fieldErrors,timestamp}`; page `{content,page,size,totalElements,totalPages}`; Clock.systemUTC được inject. Không thêm success/data wrapper. |
| Migration | Cao nhất tại thời điểm đối chiếu là V40. V4 có partial unique ACTIVE; V8 có partial unique PENDING và fail nếu dữ liệu trùng. V12 chuyển maxStaff lên package. Không sửa migration đã có. |
| Tests/docs đã đối chiếu | `AuthIntegrationTest`, `SubscriptionModuleIntegrationTest`, các test Clock/Validity/Snapshot, `AdminManagementIntegrationTest`, payment policy/integration, `FlywayCleanMigrationIntegrationTest`; `subscription-api.md`, `admin-api.md`, `payment-api.md` và OpenAPI tương ứng. Đây là nguồn hiện trạng, **không phải kết quả chạy test của tác vụ này**. |

Điểm điều chỉnh so với kế hoạch tham khảo: giữ registration hiện tại; không thêm subscription mặc định; không tái dùng settlement hóa đơn; quote cần consistency cả feature global; activation cần nhận snapshot đông cứng; scheduled expiration hiện có không được gọi REQUIRES_NEW khi đã giữ restaurant lock. Bộ contract tổng thể có10 operations: năm API Phase2 đã IMPLEMENTED; năm API Phase3–5 vẫn PLANNED.

## 2. Phạm vi và quyết định đã chốt

1. Khách đăng ký trước, có OWNER và restaurant ACTIVE, đăng nhập được ngay. Sau đó chọn package và tạo purchase. Restaurant ACTIVE biểu diễn nhà hàng được phép hoạt động; subscription ACTIVE biểu diễn quyền sử dụng gói, hai trạng thái độc lập.
2. MVP chỉ mua lần đầu/mua sau khi hết gói; không early renewal, đổi gói đang ACTIVE, auto-renew, coupon, proration, refund tự động, invoice, thu hộ tiền bán món. Admin change-package hiện có giữ nguyên.
3. Chỉ OWNER đúng tenant, account và restaurant hợp lệ. Không dùng entitlement/feature active để bảo vệ các API mua gói. Staff có SUBSCRIPTION_VIEW vẫn không được mua. Không cấp SUBSCRIPTION_MANAGE cho OWNER.
4. Provider đầu tiên SEPAY, QR chuyển khoản vào **tài khoản của nền tảng POS SaaS**. VND, số nguyên đồng, `0 < amount <= 999999999999`; dùng BigDecimal ở backend, NUMERIC(14,2) ở DB với check nguyên đồng; JSON integer. Gói miễn phí/ngoại tệ trả `422 PACKAGE_NOT_PURCHASABLE`.
5. Quote TTL đúng 15 phút, khoảng hợp lệ `[quotedAt, expiresAt)`. `expiresAt == now` là hết hạn. Payment attempt cùng hạn quote, không gia hạn. Chu kỳ lấy billingCycleMonths (1..32767), billing zone cố định `Asia/Ho_Chi_Minh` tại quote, không phụ thuộc timezone/currency của restaurant.
6. Một purchase mở cho restaurant: PENDING_PAYMENT, PAID hoặc REVIEW_REQUIRED. Một attempt PENDING/purchase. Purchase không phải subscription PENDING. Lịch sử EXPIRED/CANCELLED/CLOSED/ACTIVATED không chiếm slot.
7. ACTIVE chưa hết hạn, kể cả ACTIVE có startAt ở tương lai, chặn purchase. Admin PENDING chặn purchase cho đến activate/cancel. ACTIVE đến endAt phải reconcile EXPIRED dưới lock trước khi ghi mới.
8. Package disable/sửa giá/quyền lợi chặn quote mới hoặc đổi catalog mới, không sửa quote còn hạn đã cấp. Activation từ quote không đọc lại catalog live để đổi quyền lợi.
9. Khi đã ghi PAID nhưng activation lỗi: giữ PAID, attempt SUCCESS; job retry. Xung đột nghiệp vụ chuyển REVIEW_REQUIRED, giữ `paidAt` và event xác minh. Không yêu cầu khách trả lần nữa.
10. REVIEW_REQUIRED có RECHECK và CLOSE_OFFLINE do system admin xử lý. CLOSE_OFFLINE ghi lý do + mã bằng chứng xử lý ngoài hệ thống, kết thúc CLOSED khi đủ điều kiện; **không tạo refund giả, không xóa tiền đã nhận**.
11. GET chỉ project expiry, không ghi DB/audit. `status` là trạng thái đã lưu; `displayStatus` là trạng thái hiển thị tại serverTime. FE dựa displayStatus khi chờ thanh toán, dựa status/paidAt khi đã nhận tiền. AVAILABLE không được dùng để gộp các trạng thái subscription/purchase.
12. Flag `subscription-purchase.accept-new=false` chặn ý định tạo purchase/attempt mới (`503 PURCHASE_UNAVAILABLE`), vẫn cho đọc/cancel/replay, nhận webhook, xử lý tiền và review cũ.

## 3. Kiến trúc dự kiến và trách nhiệm

Giữ modular monolith, Controller → Service → Repository → Entity/DTO. Cây dưới mô tả kiến trúc tổng thể; phần Phase2 đã tạo class thật như mục12, các class Phase3–5 vẫn dự kiến.

```text
modules/subscriptionpurchase/
  controller/          OwnerPurchaseController, SubscriptionOnboardingController,
                       AdminSubscriptionPaymentEventController
  dto/                 request/response riêng, không serialize entity
  entity/              Purchase, PaymentAttempt, PaymentEvent
  repository/          tenant-scoped reads, locks, worker claims
  service/             AccessPolicy, PurchaseEligibilityPolicy, PurchaseQuoteFactory,
                       PurchaseCommandService, PurchaseQueryService,
                       PaymentAttemptService, PaymentInboxService,
                       SubscriptionPurchaseSettlementService, PurchaseRecoveryJob
  application/port/    SubscriptionPaymentProvider, VerifiedPaymentEvent
infrastructure/subscriptionpayment/sepay/
                       WebhookController, SepayWebhookVerifier, SepayPaymentAdapter
modules/subscription/application/port/
                       PurchasedSubscriptionActivation (module subscription sở hữu)
```

- Controller validate/security/gọi service, không chứa rule giá/tenant/state. Access guard kiểm tra role/permission/account/restaurant trước idempotency và query giá. Query/command tách nhau; policy không ghi persistence.
- Quote factory dùng catalog và immutable value, không gửi HTTP. Provider port tạo hướng dẫn QR/chuẩn hóa giao dịch, adapter chứa đặc thù SePay. Verifier chỉ xác minh chữ ký/raw bytes; inbox chỉ lưu bền vững; settlement kiểm tra giao dịch; activation port chịu trách nhiệm subscription và snapshot.
- Activation port được gọi trong transaction của application orchestration, không REQUIRES_NEW; đảm bảo subscription + purchase link + audit cùng commit. Không tạo abstraction cho từng class nếu không có ranh giới thay thế.
- Reuse AuditService, Clock, BusinessException và transaction conventions. Không tái cấu trúc module khác. Không đọc entitlement lặp cho từng feature; luồng này không yêu cầu entitlement đang active.

## 4. Quote, snapshot và kỳ sử dụng

PurchaseQuote schemaVersion=1 chứa `packageId`, `packageCode`, `packageName`, `priceAmount`, `currencyCode`, `billingCycleMonths`, sorted unique `features[{code,limits}]`, `maxStaff`, `quotedAt`, `expiresAt`, `billingZoneId`. `limits` chỉ giữ các giới hạn feature thực sự; maxStaff luôn ở top level, null=unlimited. Không nhận bất kỳ giá/feature/tenant/actor/paid/startAt/endAt từ FE. Normalize packageCode trim + uppercase trước fingerprint idempotency.

Tạo quote dùng transaction ngắn **REPEATABLE_READ**, lock restaurant trước, reconcile subscription, rồi lock package. Đọc package/mappings/feature active trong cùng snapshot transaction, không ghép price từ read trước transaction. Nếu catalog bị cập nhật cạnh tranh dẫn PostgreSQL serialization/deadlock, rollback toàn bộ rồi retry tối đa 3 lần với cùng key; hết retry `409 CONCURRENT_SUBSCRIPTION_UPDATE`. Isolation này là lựa chọn dự kiến cho command quote, không thay toàn bộ datasource isolation. Package lock đồng bộ với PackageAdminService; snapshot transaction xử lý consistency của feature global mà lock package không bao phủ.

Activation: copy grants/maxStaff/packageCode từ quote thành SubscriptionFeatureSnapshot **schemaVersion=2**; `capturedAt = activatedAt = startAt = Clock.instant()` tại lần commit thành công đầu tiên. `endAt = startAt.atZone(quote.billingZoneId).plusMonths(quote.billingCycleMonths).toInstant()`; end exclusive, Java calendar-month clamp cuối tháng. `quotedAt` ở purchase không đổi; không lấy capturedAt cũ của quote thay activation. `autoRenew=false`. Gắn packageId lịch sử (FK RESTRICT); không hard-delete catalog đang được tham chiếu. Retry trả cùng subscriptionId/start/end/snapshot, không kéo dài kỳ.

## 5. Phân quyền và contract chung

| Permission đã seed ở V41 | Role bắt buộc | API |
|---|---|---|
| SUBSCRIPTION_PURCHASE_VIEW | OWNER tenant | onboarding, list/detail, current attempt |
| SUBSCRIPTION_PURCHASE_CREATE | OWNER tenant | create purchase, create attempt |
| SUBSCRIPTION_PURCHASE_CANCEL | OWNER tenant | cancel |
| SUBSCRIPTION_PAYMENT_REVIEW | system SUPER_ADMIN (`restaurantId=null`) | admin events/reconcile |

Đã seed ở V41 theo role hiện có; endpoint admin review vẫn PLANNED. JWT cũ không tự có quyền mới; Phase 2 phải kiểm tra permission thực tế theo cơ chế hiện hành, FE refresh/login lại khi quyền đổi. Missing/invalid token 401 UNAUTHORIZED; account disabled 401 ACCOUNT_INACTIVE; wrong role/permission 403 FORBIDDEN; restaurant inactive/suspend 403 RESTAURANT_INACTIVE. Tenant lấy từ CurrentTenantProvider; scoped UUID không tồn tại hoặc khác tenant cùng 404 (không lộ ID). Webhook sử dụng HMAC riêng, không dùng JWT người dùng. Không disable security toàn `/api/v1/webhooks/**`.

Tất cả response API mới có `Cache-Control: no-store`; protected responses thêm `Vary: Authorization`. POST owner yêu cầu JSON; create/attempt yêu cầu `Idempotency-Key` 8..100 ký tự `[A-Za-z0-9._:-]`. UUID là UUID chuẩn; timestamp app UTC RFC3339 kết thúc Z. Request unknown fields bị từ chối 400 INVALID_REQUEST_BODY. Error giữ convention hiện có, fieldErrors là object string map, {} khi không có lỗi field. Rate limit 429 RATE_LIMITED + Retry-After (giây), không gọi là lỗi xác thực.

Idempotency scope `(tenant, command-kind, key)`: purchase create và attempt create hai scope khác nhau. Fingerprint SHA-256 của canonical business request, bao gồm purchaseId ở attempt (body `{}`); không gồm token/header thời gian. Access → lookup key/fingerprint → replay → eligibility cho ý định mới. Cùng key + cùng payload trả 200, `Idempotency-Replayed:true`, **body resource của lần tạo đầu lưu tại DB**; lần đầu 201/false. Body replay có thể cũ; FE GET để lấy trạng thái mới. Cùng key khác payload/path trả 409 IDEMPOTENCY_KEY_REUSED, dù resource đã ACTIVATED/EXPIRED. Không expire/xóa key khi trạng thái terminal. Hai create cùng key được constraint bảo vệ, request thua đọc replay sau rollback.

Attempt: cùng key replay; key mới khi đã có PENDING trả 409 PAYMENT_ATTEMPT_PENDING, hướng FE gọi GET current. Không trả attempt dưới key mới mà không lưu mapping idempotency. Attempt hết hạn đồng nghĩa quote hết hạn, cần purchase mới. Cancel có expectedVersion; nếu đã CANCELLED trả 200 trước check version, không audit trùng. Không cần Idempotency-Key cho cancel. Admin reconcile dùng expectedVersion, không có create/replay header.

List purchase: filters status, createdFrom inclusive/createdTo exclusive UTC; tối đa 366 ngày nếu đủ hai đầu; page=0,size=20,max100; fixed sort `createdAt DESC,id DESC`. Admin events: status, reviewReason, restaurantId, purchaseId, receivedFrom inclusive/receivedTo exclusive, page/size cùng convention; default toàn bộ event, UI thường filter REVIEW_REQUIRED. Invalid range 400 VALIDATION_ERROR. Offset pagination có thể dịch khi có insert mới, refresh page0 sau mutation; không giả lập snapshot pagination.

## 6. Vòng đời và phục hồi

Mỗi dòng dưới đây là transition được phép; transition không liệt kê bị từ chối hoặc đưa review, không sửa trạng thái tùy ý. Audit cùng transaction business; job actor null, admin actor userId. Chỉ lưu ID/trạng thái/money tối thiểu, không secret/header.

### Purchase

| Trước | Action, điều kiện | Sau / side effect | Retry / audit | Phase |
|---|---|---|---|---|
| chưa có | Create; owner hợp lệ, không ACTIVE/PENDING/open purchase, package purchasable | PENDING_PAYMENT + quote immutable | Key replay, một SUBSCRIPTION_PURCHASE_CREATED | 2 |
| PENDING_PAYMENT | Tạo attempt trước expiresAt | PENDING_PAYMENT, một attempt PENDING | Key replay, SUBSCRIPTION_PAYMENT_ATTEMPT_CREATED | 3 |
| PENDING_PAYMENT | Cancel chưa có matched payment; expectedVersion đúng | CANCELLED; attempt PENDING → CANCELLED | Lần hai no-op, SUBSCRIPTION_PURCHASE_CANCELLED một lần | 2/3 |
| PENDING_PAYMENT | Job/command thấy now >= expiresAt, chưa trả tiền | EXPIRED; attempt PENDING → EXPIRED | No-op nếu đã expire; SUBSCRIPTION_PURCHASE_EXPIRED | 5 |
| PENDING_PAYMENT hoặc EXPIRED | Giao dịch khớp, paid time trong hạn, không cancelled, không blocker mới | PAID, paidAt/payment event link, attempt SUCCESS | SUBSCRIPTION_PURCHASE_PAID một lần | 4/5 |
| PENDING_PAYMENT/EXPIRED/CANCELLED | Tiền không đủ điều kiện tự cấp (sai tiền, late, sau cancel) và link được đúng attempt; không có open purchase khác | REVIEW_REQUIRED, giữ evidence; không giả PAID | SUBSCRIPTION_PAYMENT_REVIEW_REQUIRED chỉ khi chuyển mới | 4/5 |
| EXPIRED/CANCELLED | Có tiền cần review nhưng restaurant đã có open purchase mới | Giữ terminal cũ; event REVIEW_REQUIRED riêng | Không reopen để phá unique; audit event review | 5 |
| PAID | Activation đủ điều kiện; quote đã thanh toán hợp lệ | ACTIVATED + subscription ACTIVE + snapshot | Retry thấy link thì no-op; SUBSCRIPTION_ACTIVATED và SUBSCRIPTION_PURCHASE_ACTIVATED | 4 |
| PAID | Activation lỗi hạ tầng/rollback | PAID, attempt vẫn SUCCESS; lịch retry trên purchase | Không audit thành công; backoff | 4 |
| PAID | ACTIVE/PENDING xung đột, restaurant suspend, scope khác | REVIEW_REQUIRED, **paidAt giữ nguyên** | Audit review một lần; không cancel subscription khác | 4/5 |
| REVIEW_REQUIRED | RECHECK; evidence exact hợp lệ, owner/tenant/subscription eligibility được khôi phục, không purchase mới | PAID rồi activation qua cùng port | Audit PAID chỉ nếu lần đầu; activation không trùng | 5 |
| REVIEW_REQUIRED | CLOSE_OFFLINE, lý do/mã bằng chứng, không còn event unresolved của purchase | CLOSED; giữ paidAt/money/attempt SUCCESS nếu đã có | SUBSCRIPTION_PURCHASE_CLOSED; không refund giả | 5 |
| ACTIVATED | Event thanh toán thừa khác transaction | Giữ ACTIVATED, event riêng REVIEW_REQUIRED | Không gia hạn/cấp thêm; không đổi snapshot | 4/5 |
| ACTIVATED/CLOSED/CANCELLED/EXPIRED | Replay create/cancel phù hợp | Không thay đổi lifecycle | Replay/no-op theo contract; CLOSED không tự reopen | 2/5 |

CANCELLED chỉ cấm auto activation, không phủ nhận khoản tiền ngân hàng đến sau. CLOSED là xử lý ngoài hệ thống xong, không đồng nghĩa refunded. Cancel PAID/ACTIVATED/REVIEW_REQUIRED/CLOSED trả 409 PURCHASE_CANNOT_CANCEL; EXPIRED trả 409 PURCHASE_PERIOD_EXPIRED.

### Payment attempt

| Trước | Action, guard | Sau / side effect | Retry / audit | Phase |
|---|---|---|---|---|
| chưa có | Create hợp lệ với purchase còn hạn | PENDING, mã và instructions immutable | Key replay; SUBSCRIPTION_PAYMENT_ATTEMPT_CREATED | 3 |
| PENDING | Verified exact eligible payment | SUCCESS, successfulEventId/paidAt | Chỉ một event settlement/attempt; audit purchase PAID, không audit fake lần hai | 4 |
| PENDING | Quote expire / purchase cancel / đưa review trước khi thu đủ | EXPIRED / CANCELLED / CANCELLED | Một transition, audit liên quan purchase/review | 3/5 |
| PENDING | Provider xác nhận không thể thực hiện attempt, chưa có payment | FAILED, failureCode | SUBSCRIPTION_PAYMENT_ATTEMPT_FAILED; có thể tạo attempt mới nếu quote còn hạn | 3 |
| EXPIRED | Timely payment đến muộn, chưa cancel, đủ eligibility | SUCCESS | Phải dùng provider transaction time; PAID một lần | 5 |
| FAILED | Verified tiền exact trong hạn, provider vẫn cho tiền vào, không cancel | SUCCESS | Cùng settlement guard; không bỏ tiền chỉ vì attempt FAILED | 5 |
| CANCELLED/EXPIRED/FAILED | Tiền sau cancel/ngoài hạn/sai tiền | Giữ status, event REVIEW_REQUIRED | CLOSE_OFFLINE không biến thành SUCCESS nếu chưa được settlement chấp nhận | 5 |
| SUCCESS | Duplicate / activation fail | SUCCESS, immutable financial link | No-op / retry activation; không về FAILED | 4 |

Attempt PENDING không có nghĩa tiền chưa bao giờ đến: financial evidence ở events; matched successful payment mới là SUCCESS. Không cộng nhiều event thiếu tiền thành SUCCESS.

### Provider event inbox

| Trước | Action/guard | Sau / side effect | Retry / audit | Phase |
|---|---|---|---|---|
| chưa có | HMAC hợp lệ + payload parse; DB commit thành công | RECEIVED; normalized immutable evidence | SUBSCRIPTION_PAYMENT_EVENT_RECEIVED một lần; ACK200 | 4 |
| RECEIVED/RETRY_PENDING | Worker claim đến hạn bằng lease, SKIP LOCKED | PROCESSING, leaseUntil/processingToken | Claim là kỹ thuật, không audit success | 4 |
| PROCESSING | Settlement exact hợp lệ | PROCESSED; ghi PAID trong cùng settlement transaction | Audit PAID một lần; activation transaction sau | 4 |
| PROCESSED | Activation gặp blocker nghiệp vụ và purchase phải review | REVIEW_REQUIRED, giữ toàn bộ paid evidence | Audit review; RECHECK chỉ phục hồi activation nếu đã PAID, không ghi PAID lần hai | 4/5 |
| PROCESSING | DB/network transient | RETRY_PENDING + nextAttemptAt/errorCode | Không success audit; backoff | 4/5 |
| PROCESSING | Sai tiền/ref/account/time/scope hoặc tiền thừa | REVIEW_REQUIRED + reviewReason | Audit review một lần | 4/5 |
| PROCESSING | Lease hết hạn sau restart | RETRY_PENDING | Worker recovery; không bỏ event | 4 |
| REVIEW_REQUIRED | Admin RECHECK expectedVersion đúng | RETRY_PENDING, lưu review request | SUBSCRIPTION_PAYMENT_RECHECK_REQUESTED một lần; HTTP202 chỉ queue | 5 |
| REVIEW_REQUIRED | CLOSE_OFFLINE expectedVersion đúng, evidence hợp lệ | CLOSED, actor/reason/resolutionReference | SUBSCRIPTION_PAYMENT_REVIEW_CLOSED; history giữ qua AuditService | 5 |
| PROCESSED/CLOSED | Provider duplicate | Giữ nguyên; tăng deliveryCount/lastReceivedAt | ACK200; không audit PAID/activation trùng | 4 |
| RECEIVED/PROCESSING/RETRY_PENDING/REVIEW_REQUIRED | Duplicate đang xử lý | Giữ job/lease; không reset state tùy tiện | ACK200 sau xác nhận durable row; worker tiếp tục | 4 |

Lease 60s; claim transaction ngắn. Retry 5s,30s,120s,600s,1800s, sau đó 1800s; sau 24h tự chuyển REVIEW_REQUIRED với TECHNICAL_RETRY_EXHAUSTED để admin RECHECK. PAID activation retry dùng trường job riêng trên purchase; không cần webhook gửi lại để biết còn việc.

## 7. Mô hình dữ liệu đã tạo ở V41

Quy ước: NN=NOT NULL, N=nullable, I=bất biến sau insert, M=chỉ lifecycle/job được sửa. UUID generated tại server; created_at/updated_at dùng convention Clock/DB timestamp hiện có, version BIGINT NN default0 @Version. Không hard-delete cả ba bảng; retention tài chính do vận hành xác định trước release, không có job tự xóa trong MVP. Payload đã lọc thông tin, quyền đọc chỉ admin; không Authorization/secret/token/chữ ký raw. DB role ứng dụng không được sửa financial evidence sau insert; lifecycle updates chỉ allowlist cột M, trigger V41 bảo vệ cột I.

### subscription_purchases

| Cột | PostgreSQL, null/default | Khóa / ý nghĩa / mutability |
|---|---|---|
| id | UUID NN | PK, I |
| restaurant_id | UUID NN | FK restaurants RESTRICT, I |
| created_by | UUID NN | FK users RESTRICT, I; xác minh actor cùng tenant bằng composite FK bổ sung |
| package_id | UUID NN | FK packages RESTRICT, I |
| status | VARCHAR(24) NN | M; PurchaseStatus |
| quote | JSONB NN | I; full PurchaseQuote schemaVersion1; không default '{}' |
| quote_schema_version | SMALLINT NN default1 | I, check=1; khớp quote.schemaVersion |
| amount | NUMERIC(14,2) NN | I, >0, trunc(amount)=amount; =quote.priceAmount |
| currency_code | CHAR(3) NN default 'VND' | I, check VND; =quote.currencyCode |
| quoted_at, expires_at | TIMESTAMPTZ NN | I; expires_at=quoted_at+15min; khớp quote |
| idempotency_key | VARCHAR(100) NN | I; unique restaurant+key, scope CREATE_PURCHASE |
| request_hash | CHAR(64) NN | I; SHA256 canonical packageCode |
| creation_response | JSONB NN | I; PurchaseView tại insert, phục vụ replay original body |
| paid_at | TIMESTAMPTZ N | M từ null sang thời điểm verified transfer; không xóa/ghi lại |
| paid_event_id | UUID N | M set-once; FK event cùng tenant+purchase, DEFERRABLE do cyclic link |
| subscription_id | UUID N | M set-once; UNIQUE non-null; composite FK cùng restaurant |
| activated_at, cancelled_at, closed_at | TIMESTAMPTZ N | M, set-once theo lifecycle |
| cancel_reason | VARCHAR(500) N | M set-once khi cancel |
| review_reason | VARCHAR(60) N | M; reason hiện tại, history trong audit |
| activation_retry_count | INTEGER NN default0 | M, >=0 |
| activation_next_at, activation_lease_until | TIMESTAMPTZ N | M; durable activation job |
| activation_token | UUID N | M; lease fencing token |
| activation_error_code | VARCHAR(60) N | M; sanitized, không stack trace |
| created_at, updated_at | TIMESTAMPTZ NN | created I / updated M |
| version | BIGINT NN default0 | M; optimistic lock >=0 |

Constraints/indexes: UNIQUE(id,restaurant_id); UNIQUE(id,restaurant_id,expires_at) để attempt FK hạn; UNIQUE(restaurant_id,idempotency_key); partial UNIQUE(restaurant_id) WHERE status IN ('PENDING_PAYMENT','PAID','REVIEW_REQUIRED'); UNIQUE(subscription_id) WHERE NOT NULL; UNIQUE(paid_event_id) WHERE NOT NULL. FK `(subscription_id,restaurant_id)` -> restaurant_subscriptions `(id,restaurant_id)`; `(created_by,restaurant_id)` -> users `(id,restaurant_id)` cần supporting unique trên bảng hiện có (không thay record). status check; paidAt+paidEventId cùng null/cùng non-null; PAID/ACTIVATED bắt buộc paid evidence; ACTIVATED cần subscription/activatedAt; subscriptionId chỉ hiện khi ACTIVATED. REVIEW_REQUIRED có thể paid hoặc chưa paid. Index `(restaurant_id,created_at DESC,id DESC)`, `(status,expires_at)`, partial `(activation_next_at,id)` cho PAID.

### subscription_payment_attempts

| Cột | PostgreSQL, null/default | Khóa / ý nghĩa / mutability |
|---|---|---|
| id | UUID NN | PK I |
| restaurant_id, purchase_id | UUID NN | I; composite FK purchase cùng tenant |
| status | VARCHAR(16) NN | M; AttemptStatus |
| provider | VARCHAR(20) NN default 'SEPAY' | I; check SEPAY |
| provider_account_key | VARCHAR(100) NN | I; ID config tin cậy của tài khoản nền tảng, không secret |
| reference_code | VARCHAR(40) NN | I; mã chuyển khoản global UNIQUE, không tái sử dụng |
| amount, currency_code | NUMERIC(14,2) NN, CHAR(3) NN | I; amount>0 nguyên, VND; bằng purchase |
| expires_at | TIMESTAMPTZ NN | I; **bằng** purchase.expires_at, composite FK hạn |
| instructions | JSONB NN | I; bankCode/accountNumber/accountName/transferContent/qrPayload; không credential |
| idempotency_key, request_hash | VARCHAR(100) NN, CHAR(64) NN | I; canonical purchaseId+{} |
| creation_response | JSONB NN | I; PaymentAttemptView ban đầu cho replay |
| successful_event_id | UUID N | M set-once; UNIQUE; composite FK event đúng tenant/purchase/attempt |
| paid_at | TIMESTAMPTZ N | M set-once; SUCCESS cần cùng event |
| failure_code | VARCHAR(60) N | M sanitized |
| created_at, updated_at, version | TIMESTAMPTZ NN, TIMESTAMPTZ NN, BIGINT NN default0 | I/M/M |

Constraints/indexes: UNIQUE(id,restaurant_id,purchase_id); UNIQUE(restaurant_id,idempotency_key) (scope CREATE_PAYMENT_ATTEMPT, path có trong fingerprint); partial UNIQUE(purchase_id) WHERE status='PENDING'; partial UNIQUE(purchase_id) WHERE status='SUCCESS' (MVP chỉ chấp nhận một lần trả đủ cho purchase); UNIQUE(reference_code); FK `(purchase_id,restaurant_id,expires_at)` -> purchase `(id,restaurant_id,expires_at)` bảo đảm cùng tenant và không vượt hạn. DEFERRABLE constraint trigger kiểm tra amount/currency bằng purchase khi insert; financial fields immutable nên không drift. Check status, expiresAt>createdAt, SUCCESS có paidAt/successfulEventId; UNIQUE(successful_event_id) non-null. Index `(purchase_id,created_at DESC,id DESC)`, `(status,expires_at)`.

Reference format (V42): `PS` + 20 ký tự hex uppercase từ secure random 80bit; ví dụ `PS0123456789ABCDEF0123`. Tổng 22 ký tự để vừa trường Purpose of Transaction (EMVCo tag 62-08, tối đa 25 ký tự) của VietQR; SePay cấu hình tiền tố `PS`, hậu tố 20 ký tự. Unique constraint + retry collision, không dùng restaurantId tăng dần. QR này chứa tài khoản và reference, không phải secret cấp entitlement. MVP generate payload VietQR qua adapter offline và FE render QR từ `qrPayload`, không đưa token vào QR; không lưu ảnh hosted có secret. Nếu provider phải gọi HTTP khi triển khai, prepare outside lock rồi revalidate transaction, không giữ lock chờ HTTP.

### subscription_payment_events

| Cột | PostgreSQL, null/default | Khóa / ý nghĩa / mutability |
|---|---|---|
| id | UUID NN | PK I |
| provider, provider_account_key | VARCHAR(20) NN, VARCHAR(100) NN | I; SEPAY + config account namespace |
| provider_transaction_id | VARCHAR(180) NN | I; namespace `BANK:<reference>` nếu bank reference tin cậy, nếu thiếu `WEBHOOK:<id>`/`API:<uuid>` |
| provider_webhook_id | BIGINT N | M chỉ bổ sung alias đã chứng minh; webhook payload.id |
| provider_api_transaction_id | UUID N | M chỉ bổ sung alias đã chứng minh; API v2 id |
| bank_reference | VARCHAR(150) N | I nếu có; normalized ref của ngân hàng |
| source | VARCHAR(24) NN | I; WEBHOOK hoặc RECONCILIATION |
| payload_hash | CHAR(64) NN | I; hash raw bytes ban đầu |
| payload | JSONB NN | I; allowlist payload, không header/secret; raw bytes chỉ dùng tạm verify |
| direction | VARCHAR(3) NN | I; in/out |
| amount | NUMERIC(14,2) NN | I; >0 nguyên đồng |
| currency_code | CHAR(3) NN default 'VND' | I; VND |
| receiver_account, receiver_bank | VARCHAR(100) NN | I; dữ liệu giao dịch; phải khớp trusted config khi settlement |
| payment_reference | VARCHAR(40) N | I; exact reference trích từ content/code theo quy tắc |
| occurred_at, received_at | TIMESTAMPTZ NN | I; ngân hàng time / lần nhận đầu |
| restaurant_id, purchase_id, attempt_id | UUID N | M chỉ từ null sang link xác minh cùng tenant, không reassign |
| state | VARCHAR(24) NN default 'RECEIVED' | M; EventStatus |
| review_reason | VARCHAR(60) N | M; ReviewReason |
| retry_count, delivery_count | INTEGER NN default0, INTEGER NN default1 | M >=0 / >=1 |
| next_attempt_at, lease_until | TIMESTAMPTZ N | M; recovery |
| processing_token | UUID N | M; fencing claim |
| last_received_at, processed_at | TIMESTAMPTZ NN, TIMESTAMPTZ N | M |
| last_error_code | VARCHAR(60) N | M sanitized |
| reviewed_by | UUID N | M FK users, phải system SUPER_ADMIN ở guard |
| reviewed_at | TIMESTAMPTZ N | M |
| review_note, resolution_reference | VARCHAR(1000) N, VARCHAR(200) N | M; lý do và bằng chứng ngoài hệ thống |
| created_at, updated_at, version | TIMESTAMPTZ NN, TIMESTAMPTZ NN, BIGINT NN default0 | I/M/M |

Constraints/indexes: UNIQUE(provider,provider_account_key,provider_transaction_id); partial unique cùng namespace cho provider_webhook_id, provider_api_transaction_id, bank_reference non-null. UNIQUE(id,restaurant_id,purchase_id,attempt_id) và UNIQUE(id,restaurant_id,purchase_id) hỗ trợ FK vòng; links all-null hoặc all-non-null, composite FK `(attempt_id,restaurant_id,purchase_id)` -> attempt. Unmatched event không bị ép có restaurantId. Receiver account vẫn cần match config, chữ ký không đủ để cho tiền vào sai account thành hợp lệ. Index `(state,next_attempt_at,id)`, `(state,lease_until)`, `(received_at DESC,id DESC)`, `(restaurant_id,received_at DESC)`, `(purchase_id,state)`. REVIEW_REQUIRED cần reason; CLOSED cần reviewedBy/reviewedAt/note/reference. Giữ AuditService history cho nhiều lần RECHECK/CLOSE, không overwrite lịch sử bằng một note duy nhất.

**Khác biệt định danh SePay:** webhook `id` là integer; API v2 `id` là UUID. Không cast/so sánh hai field như cùng một ID. Dedupe chéo nguồn bằng bank reference + trusted account và fingerprint (amount/direction/occurredAt/reference); bổ sung alias chỉ sau khi chứng minh. Thiếu bank reference/correlation không chắc chắn: API-import event vào review `IDENTITY_UNCERTAIN`, không tự cấp gói; webhook vẫn dedupe theo ID riêng. Một attempt đã SUCCESS không cấp lần hai, kể cả alias chưa hợp nhất. Hai ngân hàng transfers khác bank ID không được tự coi duplicate; nếu canonical identity xung đột, review `IDENTITY_CONFLICT`, không overwrite financial evidence gốc.

Migration hiện thực là **V41**, được chọn sau khi xác minh repository dừng ở V40. Phase2 đã tạo cả ba bảng, supporting unique/FK/immutable checks/indexes và permission mappings trong cùng migration để không có FK tham chiếu bảng chưa tồn tại; Phase3/4 mới dùng các bảng attempt/event. Chưa expose endpoint hoặc ghi thành công giả cho phase chưa sẵn sàng. Preflight dữ liệu trùng trước unique mới và fail rõ, không xóa dữ liệu. Giữ unique ACTIVE/PENDING subscription. Phase1 không tạo SQL; Phase2 đã tạo V41 theo schema này. Vòng FK paid event/attempt dùng DEFERRABLE INITIALLY DEFERRED, settlement transaction phải hoàn tất links trước commit.

## 8. Lock ordering và sequence transaction

Global thứ tự cho transaction phối hợp mới: **restaurant → purchase (UUID tăng nếu cần nhiều row) → attempts (UUID tăng nếu cần nhiều row) → subscription → package (chỉ quote/admin) → event finalization**. Không transaction nào giữ event lease lock rồi chờ restaurant. Claim event một transaction riêng commit trước xử lý. Đọc ID định tuyến trước lock là read-only; phải re-read/revalidate sau lock. Admin hiện chỉ restaurant→subscription→package tương thích; Phase2 thêm purchase check/lock sau restaurant, trước subscription. Existing expiration batch chỉ lock subscription, không chờ restaurant nên không tạo vòng ngược; không bổ sung reverse lock vào job.

1. **Create purchase (Phase 2):** access guard → normalize → tenant/key lookup replay → begin REPEATABLE_READ → restaurant lock → recheck access/flag → recheck key → expire due open purchase và ACTIVE cũ bằng `expireIfDue` trong transaction hiện tại → eligibility không ACTIVE/PENDING/open purchase → package lock + consistent catalog reads → quote, row + creation_response + audit → commit →201. Chỉ business success được audit. Nếu reject làm transaction rollback thì reconciliation trong cùng transaction cũng rollback; job sẽ reconcile lại, không hứa expiry đã commit khi command lỗi.
2. **Create attempt (Phase 3):** access → key/fingerprint replay → restaurant lock → scoped purchase lock → check remaining lifetime/flag/state → expire stale PENDING under lock → no PENDING attempt → immutable instructions/unique reference + creation_response + audit →commit201. Không gọi HTTP provider dưới lock. QR preparation failure trước commit trả503, không ghi attempt thành công giả; FAILED chỉ dùng khi đã có attempt/provider xác nhận thất bại.
3. **Webhook ingest (Phase 4):** bounded raw body → verify HMAC/timestamp → parse payload allowlist → DB insert inbox hoặc select existing unique match → commit →HTTP200 `{"success":true}`. DB down trả503 để provider retry, không ACK trước commit. Duplicate fingerprint conflict giữ bản gốc, ghi sanitized security/audit marker, đưa review sau lock ordering chuẩn. Duplicate nhận trong PROCESSING không reset lease hay ngưng worker. Không cần owner token.
4. **Đối soát (Phase 5):** job gọi provider API **ngoài transaction**, window thời gian có overlap và phân trang, import inbox theo canonical identity; chưa chứng minh identity phải review. Sau durable import đi cùng worker settlement. RECHECK chỉ queue event đã lưu, không gọi HTTP trong request dưới lock. Chạy mỗi5 phút, lookback48h, checkpoint trong cấu hình job có thể tái dựng từ DB; restart được replay window, unique chống trùng. Admin có thể mở rộng window qua thao tác vận hành, không cần thêm endpoint public.
5. **Ghi PAID (Phase4):** claim event commit → xác định reference exact → restaurant lock → purchase → các attempt liên quan theo UUID → subscription → event + validate fencing token → check trusted account/incoming/exact amount/reference/time/eligibility → attempt đích SUCCESS + purchase PAID + paidEventId + event PROCESSED + audit PAID trong **một transaction** → commit. Cùng transaction phải CANCELLED các attempt PENDING khác của purchase (có thể có khi một attempt FAILED cũ nhận tiền trong hạn). Nếu purchase đã có paidEventId thì event khác là DUPLICATE_PAYMENT, kể cả khác attempt; không SUCCESS lần hai hoặc overwrite paid evidence. Retry đúng paid event chỉ no-op/queue activation. Khi purchase vào review chưa PAID cũng cancel các attempt PENDING để ngừng mời chuyển tiền. Không sử dụng FE paid flag; xung đột review không sửa financial evidence.
6. **Activation (Phase4):** durable scanner lấy PAID đến hạn → restaurant lock → purchase → attempt → subscription → eligible/quote evidence → activation port tạo subscription ACTIVE từ quote → purchase ACTIVATED + unique subscriptionId + audit SUBSCRIPTION_ACTIVATED/SUBSCRIPTION_PURCHASE_ACTIVATED → commit. Audit lỗi rollback toàn activation transaction; PAID/event PROCESSED đã commit trước vẫn tồn tại. Business blocker: khóa event cuối theo lock ordering, đưa purchase và paid event về REVIEW_REQUIRED, giữ paidAt/attempt SUCCESS. Nhánh retry phục hồi từ DB, không phụ thuộc event in-memory. Khi RECHECK event đã có PAID, settlement xác minh lại evidence và eligibility rồi queue activation; không ghi lại paidAt/P audit.
7. **Cancel (Phase 2/3):** access → restaurant →purchase →attempt. CANCELLED trả200 no-op trước version. PAID/review/activated không được cancel. Check expectedVersion trước write; cancel purchase/attempt + audit cùng transaction. Nếu webhook chưa settlement mà cancel thắng lock, tiền sau đó vào review; nếu settlement thắng, cancel409. Không có khoảng race gây mất tiền.
8. **Expiry (Phase 5):** query candidates không lock dài → mỗi restaurant transaction thứ tự chuẩn → PENDING_PAYMENT chưa paid và due chuyểnEXPIRED/attemptEXPIRED+audit. PAID/REVIEW_REQUIRED không bị expiry job xóa paid evidence. Timely transfer được xét theo occurredAt, không chỉ receivedAt.
9. **Retry activation (Phase 4/5):** claim PAID job bằng short lease, release claim transaction; application transaction như bước6, fencing/recheck. Already linked subscription →return no-op; transient rollback →schedule; business blocker →review. Replay không sửa start/end.
10. **Admin gán gói cạnh tranh (Phase 2 hook, không đổi URL/body):** cùng restaurant lock; create/activate/change-package đều chặn nếu có open purchase bằng409 SUBSCRIPTION_PURCHASE_IN_PROGRESS. Tình huống bình thường ACTIVE đã chặn mua nên change-package không gặp open purchase. Cancel subscription hiện tại vẫn theo rule cũ, không tự xử lý purchase. Nếu admin thắng tạoPENDING trước, ownercreate409SUBSCRIPTION_PENDING_EXISTS; owner thắng thì admin409. Mọi code path mới cấp ACTIVE phải tuân phối hợp này. Sai lệch legacy/directSQL không tự cancel gói; PAID vào review.

Catch unique/optimistic/deadlock/serialization: map domain constraint biết rõ thành PURCHASE_IN_PROGRESS/PAYMENT_ATTEMPT_PENDING/IDEMPOTENCY_KEY_REUSED; còn lại409 CONCURRENT_SUBSCRIPTION_UPDATE. Translate ở boundary sau rollback, không tiếp tục dùng transaction đã abort. Retry nội bộ bounded với key cũ; financial uniqueness vẫn là bảo vệ cuối cùng.

## 9. SePay và ngoại lệ

MVP chọn HMAC-SHA256: `X-SePay-Signature: sha256=<hex>`, `X-SePay-Timestamp` Unix seconds; tính HMAC trên `timestamp + '.' + raw body`, constant-time compare; Clock kiểm tra lệch tối đa300giây. Timestamp ký không phải transactionDate. Chỉ nhận JSON trong contract MVP. Chi tiết đã xác minh từ [xác thực chính thức](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc).

Payload webhook có id integer, transferType in/out, transferAmount nguyên dương, code có thể null, referenceCode có thể rỗng, transactionDate giờ Việt Nam (`yyyy-MM-dd HH:mm:ss`, parse Asia/Ho_Chi_Minh). ACK chọn HTTP200 + `{"success":true}` sau lưu inbox; không trả202 cho provider. Giới hạn30giây là provider contract, thiết kế ingest mục tiêu <2giây. Nguồn: [tích hợp webhook](https://developer.sepay.vn/vi/sepay-webhooks/tich-hop-webhook).

Provider API đối soát là API v2, id UUID, reference_number riêng; adapter normalize theo loại nguồn, không ép bằng webhook integer id. Lấy nhiều trang, không chỉ trang đầu. Nguồn: [đối soát](https://developer.sepay.vn/vi/sepay-webhooks/doi-soat-giao-dich), [API danh sách giao dịch](https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/danh-sach). Đây là đọc tài liệu, chưa gọi API tài khoản hoặc tích hợp runtime.

| Tình huống | Quyết định / dữ liệu giữ / hướng phục hồi |
|---|---|
| Thiếu/thừa tiền | Event REVIEW_REQUIRED AMOUNT_MISMATCH; không cộng nhiều event, không tự PAID. Link được purchase thì đưa review/cancel attempt PENDING. Admin CLOSE_OFFLINE hoặc RECHECK sau khi chứng minh dữ liệu provider, không tự đổi amount quote. |
| Sai/thiếu mã | REFERENCE_UNMATCHED/REFERENCE_AMBIGUOUS, event giữ unmatched; không match bằng số tiền/tên khách. CLOSE_OFFLINE sau xác minh ngoài hệ thống; không có API gán tùy tiện vào purchase khác trong MVP. |
| Sai account/ngân hàng hoặc tiền ra | ACCOUNT_MISMATCH/OUTGOING_TRANSFER, event review, không PAID. Không lấy account của payload làm trusted config. |
| Webhook trùng | Unique theo provider/account/identity; ACK200, không PAID/audit/activation lần hai; worker tiếp tục nếu event còn pending. |
| Hai transfer thật khác ID | Lần exact đầu có thể PAID/ACTIVATED; lần sau DUPLICATE_PAYMENT review riêng. Purchase ACTIVATED giữ nguyên. Không cấp thêm/renew. |
| Transfer sau cancel | PURCHASE_CANCELLED, không auto activate dù occurredAt trước deadline; admin giải quyết ngoài hệ thống. |
| Transfer ngoài `[attempt.createdAt,quote.expiresAt)` | PAYMENT_OUTSIDE_WINDOW, review; QR hết hạn không ngăn ngân hàng nhận tiền. Không dùng reference cũ cho purchase mới. |
| Transfer đúng hạn nhưng webhook trễ | Nếu purchase không cancel/CLOSED và không có ACTIVE/PENDING/open purchase khác, xét occurredAt để PAID/activate từ quote cũ; nếu blocker mới thì review. Kiểm tra receivedAt không thay occurredAt. |
| Catalog sửa/disable sau quote | Trong hạn + payment hợp lệ vẫn honor quote cũ; không đọc live grants. Quote mới dùng catalog hiện tại. |
| Restaurant/account bị suspend | Owner API bị guard chặn. Provider vẫn lưu tiền; suspend restaurant dẫn review RESTAURANT_UNAVAILABLE, không activate tự động. Có OWNER hợp lệ/restaurant ACTIVE trở lại mới RECHECK. |
| Provider không truy cập | Create attempt offline vẫn tạo instructions nếu config hợp lệ; truy vấn đối soát retry. Nếu cần QR HTTP mà unavailable:503 PAYMENT_PROVIDER_UNAVAILABLE, không chặn đọc trạng thái/activation payment đã xác minh. |
| DB lỗi ở ingest |503 WEBHOOK_PERSISTENCE_UNAVAILABLE, không ACK, provider retry. |
| DB/audit lỗi ở activation | Rollback activation; giữ PAID/SUCCESS; job retry, không thu thêm. |
| Webhook thất lạc/restart | Poll reconciliation import durable inbox; recover expired lease; unique chặn xử lý trùng. |
| Reload/đổi thiết bị | GET onboarding/list/detail/current; không auto POST mới; restore theo server resource. Không cần giữ mật khẩu hoặc raw token trong QR. |
| Event identity/fingerprint bất nhất | IDENTITY_CONFLICT/IDENTITY_UNCERTAIN; không overwrite evidence. Admin review; không khẳng định hai nguồn khác ID là hai khoản tiền thật. |

Reference extraction: uppercase ASCII, tách token theo ranh giới, đúng `PS[0-9A-F]{20}`; code nếu có phải khớp token content. Zero token →UNMATCHED, >1 khác nhau →AMBIGUOUS, không substring/fuzzy match. Tìm attempt historical đúng reference, không tìm purchase mới nhất theo amount.

## 10. Onboarding projection và API/error

`subscriptionState`: NONE/PENDING/ACTIVE/EXPIRED/CANCELLED; ưu tiên effective hoặc stored ACTIVE/PENDING (kể cả future ACTIVE), nếu không có thì subscription lịch sử mới nhất theo createdAt/id. Stored ACTIVE đã endAt<=now project EXPIRED, currentSubscription vẫn cho biết stored status/effective/start/end. `currentPurchase`: open purchase; stale PENDING_PAYMENT còn lưu DB hiển thị displayStatus EXPIRED, không phải cơ hội tạo record bỏ qua command lock. `unresolvedPaymentReviewCount`: đếm event REVIEW_REQUIRED toàn tenant, độc lập với subscription ACTIVE và currentPurchase. Event thừa ở purchase ACTIVATED không chuyển subscriptionState.

`canPurchase` chỉ true khi có CREATE permission, flag cho phép, không ACTIVE/PENDING, không open purchase còn hiệu lực/paid/review. Stale purchase sẽ được reconcile trước create. blockedReason ưu tiên PURCHASE_UNAVAILABLE → PURCHASE_PERMISSION_REQUIRED → SUBSCRIPTION_ALREADY_ACTIVE → SUBSCRIPTION_PENDING_EXISTS → PURCHASE_REVIEW_REQUIRED → PURCHASE_IN_PROGRESS. Review event độc lập ở purchase terminal không chặn mua mãi; currentPurchase REVIEW_REQUIRED mới chiếm slot. allowedActions chỉ là UI hint, backend guard quyết định cuối.

Toàn bộ10 operations và schema/error cụ thể nằm trong OpenAPI. Các nhóm error ổn định:401 UNAUTHORIZED/ACCOUNT_INACTIVE/WEBHOOK_AUTHENTICATION_FAILED/WEBHOOK_TIMESTAMP_INVALID;403 FORBIDDEN/RESTAURANT_INACTIVE;404 PACKAGE_NOT_FOUND/PURCHASE_NOT_FOUND/PAYMENT_EVENT_NOT_FOUND;400 VALIDATION_ERROR/INVALID_REQUEST_BODY;409 SUBSCRIPTION_ALREADY_ACTIVE/SUBSCRIPTION_PENDING_EXISTS/PURCHASE_IN_PROGRESS/PURCHASE_PERIOD_EXPIRED/PURCHASE_CANNOT_CANCEL/PURCHASE_REVIEW_REQUIRED/PAYMENT_ATTEMPT_PENDING/PAYMENT_ATTEMPT_NOT_ALLOWED/IDEMPOTENCY_KEY_REUSED/CONCURRENT_SUBSCRIPTION_UPDATE/INVALID_PAYMENT_EVENT_TRANSITION/SUBSCRIPTION_PURCHASE_IN_PROGRESS;413 WEBHOOK_PAYLOAD_TOO_LARGE (raw body >64KiB);422 PACKAGE_NOT_PURCHASABLE;429 RATE_LIMITED;500 INTERNAL_ERROR (fallback hiện có);503 PURCHASE_UNAVAILABLE/PAYMENT_PROVIDER_UNAVAILABLE/WEBHOOK_PERSISTENCE_UNAVAILABLE/SERVICE_UNAVAILABLE. `ReviewReason` và `blockedReason` là enum riêng trong OpenAPI, không phải HTTP error code. Không đổi lỗi entitlement SUBSCRIPTION_NOT_ACTIVE hiện có.

## 11. Kế hoạch hiện thực tiếp theo và đầu vào môi trường

| Phase | Phạm vi / trạng thái | Điểm nghiệm thu |
|---|---|---|
| 1 — tài liệu | Bốn file hiện tại | OpenAPI validate, refs/examples đúng, chỉ docs thay đổi |
| 2 — purchase/onboarding | IMPLEMENTED: V41, bốn permission, command/query/access/quote, admin guard, idempotency và năm API create/read/cancel/onboarding | Không cần subscription để mua; concurrency tenant/key/quote; không expose API QR chưa hỗ trợ |
| 3 — attempt/QR | IMPLEMENTED: V42, port `PaymentInstructionProvider` + adapter SePay offline (VietQR payload + qr.sepay.vn), create/current attempt, featureflag, expiry command và job | Reference unique, một PENDING, no lock HTTP, khôi phục reload |
| 4 — verified payment/activation | IMPLEMENTED: event inbox, HMAC, worker, settlement+activation port, durable PAID retry | ACK sau commit; rollback recover; exact quote grants; không cấp trùng |
| 5 — vận hành | Jobs expiry/reconciliation/review, admin API, late payment, audit history/metrics | Không bỏ tiền muộn, CLOSE_OFFLINE có evidence, identity chéo nguồn đúng |
| 6 — web | Theo FE sync, flags chỉ bật khi Phase2–5 sẵn sàng | E2E đăng ký→mua→activate/recover, không tự gọi API planned trước release |
| 7 — release | Maven toàn bộ integration + Flyway clean/upgrade + concurrency + failure injection + web E2E | Test plan pass thực tế, backup/rollback deployment; tắt nhận mới vẫn xử lý tiền cũ |

Trước tích hợp cần cung cấp: hostname HTTPS webhook và web origin, sandbox/live separation, bankCode/accountNumber/accountName của nền tảng, trusted providerAccountKey + SePay bank_account_id, secret HMAC ở secret store và quy trình rotation, API token đối soát (khác secret webhook), cấu hình prefix PS ở SePay, timezone/NTP, worker schedule/observability, flags/DB grants, người vận hành và cách lưu bằng chứng CLOSE_OFFLINE. Không điền secret/tài khoản thật vào tài liệu mẫu. Prefix/QR acceptance phải xác minh bằng sandbox trước bật live. Không hardcode migration V41; đánh số lại tại thời điểm hiện thực.

Phase1 chỉ validate tài liệu; Phase2 đã thêm runtime tests, xem test plan và báo cáo xác minh. Các case provider/QR/activation/web tương lai vẫn NOT RUN.

## 12. Giai đoạn 2 đã hiện thực

V41 tạo đủ ba bảng và FK vòng, constraints/indexes, trigger bất biến/chống hard-delete, bốn permission và mapping OWNER/system SUPER_ADMIN. Không sửa V1–V40, không backfill subscription/quote tài chính cũ. Runtime có đúng năm API Phase2; attempts/events hiện chỉ có schema và các query thật để count review/cancel/expire pending attempts. Không tạo provider, worker, webhook hoặc activation port rỗng. [API/Postman](subscription-purchase-api.md).

Module mới dùng PurchaseAccessPolicy (persisted role/account/permission và giao JWT grants), PurchaseEligibilityPolicy, PurchaseQuoteFactory, PurchaseCommandService, PurchaseQueryService, PurchaseExpirationService và PurchaseResponseMapper. Entity giữ timestamp từ Clock riêng, không thay BaseAuditable đang có Instant.now(). Request converter chỉ áp dụng hai DTO purchase, dùng Spring7 configureMessageConverters(ServerBuilder), giữ response mapper và API khác.

Create boundary dùng TransactionTemplate REQUIRES_NEW/REPEATABLE_READ, tối đa3 transaction độc lập cho serialization/deadlock; không được gọi khi caller đã giữ restaurant lock. Cancel dùng transaction độc lập. Admin guard MANDATORY chạy trong transaction admin hiện có, sau restaurant lock và trước subscription/package lock. Không đưa REQUIRES_NEW expiration vào bên trong lock đã giữ. Creation response đầy đủ được lưu ngay INSERT nhờ UUID do server cấp trước persist; replay trả body ban đầu, không tái dựng theo catalog/current state.

**Điều chỉnh concurrency khi hiện thực:** restaurant SELECT FOR UPDATE một mình không làm mới snapshot REPEATABLE_READ đã bắt đầu trước lúc chờ lock. Admin guard sau khi kiểm tra purchase còn thực hiện một MVCC touch trên restaurant (`UPDATE ... SET id=id` dưới write lock). Buyer chờ admin commit nhận serialization conflict, rollback và retry bằng snapshot mới, rồi thấy subscription PENDING/ACTIVE. Không thêm cột coordinator hoặc bảng thứ tư. Trigger updated_at hiện có của restaurant ghi nhận lần phối hợp subscription thành công; không ghi audit RESTAURANT_PROFILE_UPDATED giả. Request admin bị reject rollback cả touch. Test đợi PostgreSQL wait_event_type=Lock thật để kiểm tra race, không dựa sleep.

Purchase mutation refresh restaurant dưới lock để tránh entity status cũ trong OSIV cache. Owner permissions ở access guard là giao giữa JWT và quyền hiện còn trong DB; token cũ không được tự nâng quyền, grant bị revoke không tiếp tục replay. Role OWNER hiện tại trong DB cũng phải hợp lệ. Admin coordination vẫn chạy khi flag nhận mới tắt. Staff assignment policy loại trừ rõ cả bốn permission SaaS mới.

Flag `subscription-purchase.accept-new` đọc biến SUBSCRIPTION_PURCHASE_ACCEPT_NEW, mặc định false. Chỉ local/test bật true để tạo quote. READ/replay/cancel vẫn dùng được khi false; Phase2 không quảng bá CREATE_PAYMENT_ATTEMPT/QR. GET chỉ project expiry. Chưa có rate limiter mới, nên các operation Phase2 không còn khai báo429 như quota đã hoạt động.

Các class/flow activation, provider và review ở mục3–11 vẫn là thiết kế cho Phase3–5. Purchase trạng thái PAID/ACTIVATED/review có thể được đọc từ fixture hợp lệ nhưng Phase2 không có đường runtime ghi nhận tiền/cấp gói. Test payment-related dùng fixture có FK/constraints đầy đủ; không chứng minh webhook hoặc thanh toán đã hoạt động.
