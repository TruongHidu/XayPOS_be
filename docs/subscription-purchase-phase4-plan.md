# Giai đoạn 4 — Webhook SePay, ghi nhận thanh toán và kích hoạt gói (PLAN)

Ngày lập: 2026-11-02. Trạng thái: **IMPLEMENTED** theo khuyến nghị D1–D5; các điểm điều chỉnh khi hiện thực nằm trong [báo cáo Phase4](subscription-purchase-phase4-implementation.md). Nền: Phase3 đã có purchase → payment attempt (mã `PS` + 20 HEX, QR SePay). Schema V41 đã có bảng `subscription_payment_events` và các cột job kích hoạt trên `subscription_purchases`, nên **Phase4 dự kiến không cần migration mới**. Tài liệu liên quan: [thiết kế](subscription-purchase-design.md) §6, §8, §9; [Phase3](subscription-purchase-phase3-implementation.md); [OpenAPI](openapi/subscription-purchase-api.yaml) operation `receiveSepaySubscriptionWebhook`.

## 1. Mục tiêu và phạm vi

**Trong phạm vi**

1. `POST /api/v1/webhooks/subscriptions/sepay`: xác thực HMAC SePay, lưu giao dịch vào inbox bền vững, chỉ ACK sau khi đã lưu.
2. Worker đối chiếu (settlement): giao dịch khớp mã, số tiền, tài khoản và thời gian → attempt `SUCCESS` + purchase `PAID`. Giao dịch không khớp → `REVIEW_REQUIRED` kèm lý do, không mất tiền.
3. Kích hoạt gói: purchase `PAID` → subscription `ACTIVE` dựng từ quote đã chốt → purchase `ACTIVATED`. Lỗi kỹ thuật thì retry bền vững; xung đột nghiệp vụ thì chuyển review.
4. Phục hồi: lease hết hạn sau restart, backoff retry, quá 24h thì chuyển review `TECHNICAL_RETRY_EXHAUSTED`.

**Ngoài phạm vi (Phase5):** job đối soát gọi API SePay, API admin xem/RECHECK/CLOSE_OFFLINE event, metrics. Event và purchase ở `REVIEW_REQUIRED` sau Phase4 chỉ xử lý được bằng SQL vận hành cho đến khi có Phase5.

## 2. Thông tin SePay đã xác minh

Nguồn: [xác thực webhook](https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc), [tích hợp webhook](https://developer.sepay.vn/vi/sepay-webhooks/tich-hop-webhook), [xử lý lỗi và retry](https://developer.sepay.vn/vi/sepay-webhooks/xu-ly-loi).

| Điểm | Giá trị |
|---|---|
| Header ký | `X-SePay-Signature: sha256=<hex>`, `X-SePay-Timestamp: <unix seconds>` |
| Chuỗi được ký | `timestamp + "." + rawBody`, HMAC-SHA256 với secret tạo trong wizard webhook (chỉ hiện 4 ký tự cuối sau khi lưu) |
| Chống replay | Mẫu chính thức từ chối khi lệch quá 300 giây |
| ACK thành công | HTTP 200/201 **và** body đúng `{"success": true}` **và** trong 30 giây; 202 bị coi là lỗi |
| Retry | Nếu bật "tự động gửi lại": gửi ngay rồi chờ 1, 1, 2, 3, 5, 8, 13 phút (8 lần, khoảng 33 phút); không có thứ tự đảm bảo |
| Khóa chống trùng | `id` (integer) không đổi qua mọi lần retry/replay |
| Payload | `id`, `gateway`, `transactionDate` (`yyyy-MM-dd HH:mm:ss` giờ Việt Nam), `accountNumber`, `subAccount`, `code` (có thể null), `content`, `transferType` (`in`/`out`), `description`, `transferAmount`, `accumulated`, `referenceCode` |

Chữ ký sai trả 401 thì SePay vẫn retry đủ 8 lần. Chấp nhận được: không lưu gì, chỉ log cảnh báo đã che dữ liệu.

## 3. Quyết định cần chốt

| # | Vấn đề | Khuyến nghị |
|---|---|---|
| D1 | Cách xác thực webhook | **HMAC-SHA256** (chống sửa nội dung, có timestamp chống replay). API Key chỉ chứng minh người gửi, không bảo vệ nội dung. |
| D2 | Nguồn giao dịch | Tạo webhook SePay **chỉ cho tiền vào** của **tài khoản/VA dành riêng** cho thu phí SaaS. Nếu dùng chung tài khoản, mọi khoản tiền không có mã `PS…` sẽ thành review `REFERENCE_UNMATCHED`. Cần xác minh trong test mode `accountNumber` hay `subAccount` mang số tài khoản/VA nhận tiền để đối chiếu. |
| D3 | Lệch giờ ngân hàng | `transactionDate` chỉ có độ chính xác giây, giờ ngân hàng có thể lệch. Chấp nhận `occurredAt` trong `[attempt.createdAt − 2 phút, attempt.expiresAt + 2 phút)`, cấu hình qua `app.sepay.settlement.clock-skew=PT2M`. |
| D4 | Chuyển thiếu/thừa tiền | Theo thiết kế §6: event `REVIEW_REQUIRED AMOUNT_MISMATCH`, purchase đang chờ thì chuyển `REVIEW_REQUIRED` và hủy attempt PENDING. Không cộng nhiều khoản, không cho chuyển bù. |
| D5 | Độ trễ kích hoạt | Worker settlement gọi kích hoạt **ngay sau khi commit PAID** (transaction riêng). Job kích hoạt định kỳ chỉ là đường dự phòng khi lần đầu lỗi. Owner thường thấy `ACTIVATED` sau vài giây. |

## 4. Kiến trúc và áp dụng SOLID

```text
infrastructure/subscriptionpayment/sepay/
  SepayWebhookProperties         (@ConfigurationProperties "app.sepay.webhook": secret, tolerance, max-body)
  SepayWebhookVerifier           (HMAC + timestamp; thuần, không I/O)
  SepayWebhookAuthenticationFilter (đọc raw body có giới hạn → verifier → đặt Authentication SEPAY_WEBHOOK)
  SepayWebhookPayloadMapper      (JSON SePay → VerifiedPaymentEvent; parse giờ VN)
  SepayWebhookController         (gọi PaymentInboxService, trả {"success":true})
  SepayPlatformAccountRegistry   (implements PlatformAccountRegistry: providerAccountKey → số tài khoản tin cậy)
modules/subscriptionpurchase/
  application/port/
    VerifiedPaymentEvent         (record chuẩn hóa, không phụ thuộc SePay)
    PlatformAccountRegistry      (port: tài khoản nhận tiền tin cậy theo providerAccountKey)
  entity/
    SubscriptionPaymentEvent, PaymentEventState, ReviewReason
    SubscriptionPurchase         (+ markPaid / markActivated / markReview / scheduleActivationRetry; map cột activation_*)
    SubscriptionPaymentAttempt   (+ markSuccess)
  repository/
    SubscriptionPaymentEventRepository (claim SKIP LOCKED, recover lease, khóa theo id)
    SubscriptionPurchaseRepository     (+ claim activation PAID)
    SubscriptionPaymentAttemptRepository (+ findByReferenceCode, lock attempts theo purchase)
  service/
    PaymentInboxService          (lưu event, chống trùng, đếm delivery)
    PaymentReferenceExtractor    (tách đúng token PS[0-9A-F]{20}; thuần)
    SettlementPolicy             (quyết định PAY / REVIEW(reason) / ALREADY_SETTLED; thuần, không ghi DB)
    PaymentSettlementService     (lock theo thứ tự chuẩn, áp dụng quyết định, audit)
    PaymentEventClaimService     (claim/lease/recover/backoff; transaction ngắn)
    PurchaseActivationService    (gọi port kích hoạt, chuyển ACTIVATED hoặc review/retry)
  jobs/
    PaymentEventWorkerJob, PurchaseActivationJob
modules/subscription/
  application/port/
    PurchasedSubscriptionActivation (port do module subscription sở hữu)
    PurchasedActivationCommand, PurchasedActivationResult, ActivationBlockedException
  service/
    PurchasedSubscriptionActivationService (implements port: tạo ACTIVE + snapshot từ quote)
  domain/
    SubscriptionPeriodCalculator  (startAt + billingCycleMonths theo billingZoneId; thuần)
```

| Nguyên tắc | Áp dụng cụ thể |
|---|---|
| **S** | Verifier chỉ kiểm chữ ký. Mapper chỉ chuẩn hóa payload. Inbox chỉ lưu bền vững và chống trùng, **không** đối chiếu tiền. `SettlementPolicy` chỉ ra quyết định (thuần), `PaymentSettlementService` chỉ khóa/áp dụng/audit. Claim service chỉ quản lý lease/retry. Kích hoạt gói nằm ở module subscription. |
| **O** | Thêm nguồn giao dịch thứ hai (job đối soát Phase5, provider khác) = thêm adapter tạo `VerifiedPaymentEvent`, không sửa inbox/settlement. Thêm lý do review = thêm nhánh trong `SettlementPolicy`, có unit test riêng. |
| **L** | Mọi nguồn đều phải trả `VerifiedPaymentEvent` đã xác thực, `amount` nguyên đồng dương, `occurredAt` UTC, `providerTransactionId` ổn định qua retry. Mọi implementation `PlatformAccountRegistry` trả đúng tài khoản đã cấu hình, không lấy từ payload. |
| **I** | Ba port tách rời: `PaymentInstructionProvider` (Phase3, tạo QR), `PlatformAccountRegistry` (đối chiếu tài khoản), `PurchasedSubscriptionActivation` (kích hoạt). Webhook verifier là chi tiết của adapter, không phải port của module. |
| **D** | Module purchase không import gì từ `infrastructure.subscriptionpayment.sepay`. Module purchase gọi module subscription qua port `PurchasedSubscriptionActivation` (giống `SubscriptionPurchaseConflictGuard` hiện có nhưng theo chiều ngược lại), không gọi thẳng `SubscriptionCommandService`. |

## 5. Ingress webhook

**Security.** Thêm vào `SecurityConfig`: `.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/subscriptions/sepay").hasAuthority("SEPAY_WEBHOOK")`. Chỉ đúng một path, không `permitAll` cho cả `/api/v1/webhooks/**`. `SepayWebhookAuthenticationFilter` (đặt trước `JwtAuthenticationFilter`, chỉ chạy cho path trên):

1. `Content-Length` hoặc số byte đọc được > 64 KiB → `413 WEBHOOK_PAYLOAD_TOO_LARGE`. Đọc bằng `readNBytes(MAX + 1)` như `QrOrderBodyLimitFilter`.
2. Thiếu hoặc sai `X-SePay-Timestamp`, lệch quá 300 giây so với `Clock` → `401 WEBHOOK_TIMESTAMP_INVALID`.
3. Thiếu chữ ký, sai tiền tố `sha256=`, hoặc `MessageDigest.isEqual` (so sánh thời gian hằng) không khớp → `401 WEBHOOK_AUTHENTICATION_FAILED`.
4. Hợp lệ → đặt `Authentication` có authority `SEPAY_WEBHOOK` và chuyển request đã bọc raw body cho controller.

Lỗi được ghi bằng `SecurityErrorResponseWriter` đúng format `ErrorResponse`. Log chỉ ghi IP và lý do, không ghi chữ ký, secret hay body. `PurchaseNoStoreFilter` (hoặc filter webhook) thêm `Cache-Control: no-store`.

**Controller → inbox.** Controller nhận `byte[]`, gọi `SepayWebhookPayloadMapper` (lỗi parse sau khi chữ ký đúng → `400 INVALID_REQUEST_BODY`, log cảnh báo), rồi `PaymentInboxService.receive(event)`:

| Bước | Hành vi |
|---|---|
| Định danh | `provider_transaction_id = "BANK:" + referenceCode` khi `referenceCode` không rỗng, ngược lại `"WEBHOOK:" + id`. Luôn lưu `provider_webhook_id = id`. |
| Lưu | Transaction ngắn: insert event `RECEIVED` (`payload` chỉ giữ allowlist field, `payload_hash` = SHA-256 raw body, `received_at`/`last_received_at` = now), audit `SUBSCRIPTION_PAYMENT_EVENT_RECEIVED`. Commit → `200 {"success":true}`. |
| Trùng `id` | Unique `uq_spe_webhook` → tăng `delivery_count`, cập nhật `last_received_at`, **không** reset state/lease, không audit lại → 200. |
| Trùng bank reference, khác `id` | Cùng dấu vân tay (số tiền, hướng, `occurredAt`) → coi là alias, xử lý như trùng. Khác dấu vân tay → giữ bản gốc, chuyển event gốc sang review `IDENTITY_CONFLICT` nếu state cho phép, log cảnh báo, trả 200. |
| DB lỗi | `503 WEBHOOK_PERSISTENCE_UNAVAILABLE` để SePay retry. Không bao giờ ACK trước khi commit. |

Request webhook không đối chiếu tiền, không gọi kích hoạt, nên luôn trả lời trong khoảng vài trăm ms, xa giới hạn 30 giây.

## 6. Worker đối chiếu (settlement)

**Claim** (`PaymentEventClaimService`, transaction ngắn, commit trước khi xử lý):

```sql
select id from subscription_payment_events
where state in ('RECEIVED','RETRY_PENDING') and (next_attempt_at is null or next_attempt_at <= :now)
order by id limit :batch for update skip locked
```

→ `PROCESSING`, `lease_until = now + 60s`, `processing_token = uuid mới`. Recovery cùng job: `PROCESSING` có `lease_until < now` → `RETRY_PENDING`.

**Settle** (`PaymentSettlementService.settle(eventId, token)`, một transaction):

```text
1. Đọc event (không lock) → PaymentReferenceExtractor:
   - 0 token PS…      → finalize REVIEW REFERENCE_UNMATCHED (không link)
   - >1 token khác nhau → finalize REVIEW REFERENCE_AMBIGUOUS
   - code (nếu có) khác token trong content → REFERENCE_AMBIGUOUS
2. attempts.findByReferenceCode(ref) → không có → REFERENCE_UNMATCHED
3. Lock theo thứ tự chuẩn: restaurant (không yêu cầu ACTIVE) → purchase FOR UPDATE
   → attempts của purchase FOR UPDATE (order by id) → subscription ACTIVE/PENDING FOR UPDATE
   → event FOR UPDATE (kiểm state = PROCESSING và processing_token = token, sai → bỏ, không ghi)
4. decision = SettlementPolicy.decide(facts)        — thuần, không I/O
5. Áp dụng decision + audit, commit
```

**`SettlementPolicy`** (thứ tự kiểm tra, dừng ở điều kiện đầu tiên sai):

| # | Điều kiện | Sai thì |
|---|---|---|
| 1 | `direction == in` | `OUTGOING_TRANSFER` |
| 2 | Tài khoản nhận == `PlatformAccountRegistry.accountFor(attempt.providerAccountKey)` | `ACCOUNT_MISMATCH` |
| 3 | `amount == attempt.amount`, VND | `AMOUNT_MISMATCH` |
| 4 | `occurredAt` trong cửa sổ D3 | `PAYMENT_OUTSIDE_WINDOW` |
| 5 | Purchase đã `PAID`/`ACTIVATED` với `paid_event_id` = event này | `ALREADY_SETTLED` (no-op, event `PROCESSED`) |
| 6 | Purchase đã có `paid_event_id` khác | `DUPLICATE_PAYMENT` |
| 7 | Purchase `CANCELLED` | `PURCHASE_CANCELLED` |
| 8 | Purchase `REVIEW_REQUIRED`/`CLOSED` | Event review, lý do = `purchase.review_reason` |
| 9 | Purchase `EXPIRED` (webhook đến muộn, tiền đúng hạn): restaurant còn purchase mở khác, hoặc có subscription ACTIVE/PENDING | `SUBSCRIPTION_CONFLICT` (purchase giữ `EXPIRED`) |
| — | Qua hết | `PAY` |

**Áp dụng:**

- `PAY`: link event (restaurant, purchase, attempt) → attempt `SUCCESS` (`successful_event_id`, `paid_at = occurredAt`) → hủy các attempt PENDING khác của purchase → purchase `PAID` (`paid_event_id`, `paid_at`, `activation_next_at = now`) → event `PROCESSED` → audit `SUBSCRIPTION_PURCHASE_PAID`. FK vòng là DEFERRABLE nên cả cụm phải xong trong cùng transaction.
- `REVIEW` có attempt: link event, event `REVIEW_REQUIRED` + reason. Purchase `PENDING_PAYMENT` → `REVIEW_REQUIRED` (cùng reason) và hủy attempt PENDING (dừng mời chuyển tiền). Purchase đã terminal thì giữ nguyên. Audit `SUBSCRIPTION_PAYMENT_REVIEW_REQUIRED` một lần.
- `REVIEW` không có attempt: event review, không link (ràng buộc `ck_spe_links` yêu cầu link đủ cả ba hoặc không link).
- Lỗi kỹ thuật (DB, deadlock, serialization): rollback; transaction mới chuyển event `RETRY_PENDING`, `retry_count + 1`, `next_attempt_at` theo backoff 5s, 30s, 120s, 600s, 1800s rồi 1800s; `last_error_code` đã làm sạch. Quá 24h kể từ `received_at` → `REVIEW_REQUIRED TECHNICAL_RETRY_EXHAUSTED`.

Không bao giờ match theo số tiền hay tên người chuyển, chỉ match theo đúng mã. Không cộng nhiều khoản thiếu thành một khoản đủ.

## 7. Kích hoạt gói

**Port (module subscription sở hữu):**

```java
public interface PurchasedSubscriptionActivation {
    /** Caller holds restaurant then purchase locks; runs in the caller's transaction (MANDATORY). */
    PurchasedActivationResult activate(PurchasedActivationCommand command);   // throws ActivationBlockedException
}
record PurchasedActivationCommand(UUID restaurantId, UUID purchaseId, UUID packageId, String packageCode,
    BigDecimal priceAmount, String currencyCode, int billingCycleMonths, String billingZoneId,
    List<FeatureGrant> features, Long maxStaff, Instant activatedAt) {}
record PurchasedActivationResult(UUID subscriptionId, Instant startAt, Instant endAt) {}
```

Module purchase dựng command từ `PurchaseQuote` đã lưu. Module subscription không đọc bảng purchase và không đọc lại catalog live.

**`PurchasedSubscriptionActivationService`:**

1. Subscription ACTIVE đã quá `endAt` → `SubscriptionExpirationService.expireIfDue` (MANDATORY, cùng transaction).
2. Còn ACTIVE hiệu lực hoặc PENDING → `ActivationBlockedException(SUBSCRIPTION_CONFLICT)`.
3. Tạo `RestaurantSubscription` ACTIVE: `startAt = activatedAt`, `endAt = SubscriptionPeriodCalculator.end(startAt, billingZoneId, billingCycleMonths)` (cộng tháng theo lịch Java, tự kẹp ngày cuối tháng, `endAt` loại trừ), giá và tiền tệ từ quote, `autoRenew = false`, snapshot `schemaVersion 2` dựng từ grants + `maxStaff` của quote.
4. Audit `SUBSCRIPTION_ACTIVATED` (actor null, có `purchaseId`).

**`PurchaseActivationService.activate(purchaseId, token)`** (gọi ngay sau PAID và từ `PurchaseActivationJob`):

```text
claim (tx ngắn): PAID và activation_next_at <= now và (lease null hoặc hết hạn) → lease 60s + activation_token
tx kích hoạt: lock restaurant → purchase FOR UPDATE (kiểm PAID + token)
  → đã có subscription_id → no-op
  → restaurant không ACTIVE → review RESTAURANT_UNAVAILABLE
  → port.activate(...) → purchase ACTIVATED (subscription_id, activated_at), xóa field job
  → audit SUBSCRIPTION_PURCHASE_ACTIVATED
ActivationBlockedException → purchase REVIEW_REQUIRED (giữ paidAt, attempt SUCCESS)
  + event đã trả tiền → REVIEW_REQUIRED (khóa event cuối cùng theo thứ tự chuẩn) + audit review
Lỗi kỹ thuật → rollback; tx mới: activation_retry_count + 1, activation_next_at theo backoff, activation_error_code
```

`SubscriptionPurchaseConflictGuard` **không** được gọi trong luồng này (purchase đang kích hoạt chính là purchase mở). Unique index ACTIVE hiện có vẫn là lớp bảo vệ cuối.

## 8. Thay đổi ở phía đọc

- Owner chỉ cần poll `GET /subscription-purchases/{id}` hoặc `/subscriptions/onboarding` mỗi 3–5 giây khi đang chờ: `PENDING_PAYMENT` → `PAID` (`WAIT_ACTIVATION`) → `ACTIVATED` (`OPEN_APP`). Mapper hiện có đã xử lý các trạng thái này.
- `GET .../payment-attempts/current` trả attempt `SUCCESS` có `paidAt`; FE ẩn QR.
- `unresolvedPaymentReviewCount` ở onboarding đã đếm event `REVIEW_REQUIRED`.
- Sau `ACTIVATED`, FE gọi `GET /me/entitlements` và làm mới cache quyền.

## 9. Cấu hình

```yaml
app:
  sepay:
    webhook:
      secret: ${SEPAY_WEBHOOK_SECRET:}          # trống thì endpoint luôn trả 401
      timestamp-tolerance: PT5M
      max-body-bytes: 65536
    settlement:
      clock-skew: ${SEPAY_SETTLEMENT_CLOCK_SKEW:PT2M}
  jobs:
    subscription-payment-events:
      enabled: true
      fixed-delay: PT5S
      batch-size: 20
      lease: PT60S
      retry-exhausted-after: PT24H
    subscription-purchase-activation:
      enabled: true
      fixed-delay: PT10S
      batch-size: 20
```

Test profile tắt hai job mới trong `src/test/resources/application.properties` và gọi trực tiếp service trong integration test. Hiện chỉ có một luồng scheduler mặc định cho tất cả job; nên đặt `spring.task.scheduling.pool.size=2` để job 5 giây không phải chờ job khác.

## 10. Test local

- **Cách 1, không cần SePay:** ký payload bằng secret local rồi gọi thẳng endpoint.

```bash
ts=$(date +%s); body='{"id":1001,"gateway":"MBBank","transactionDate":"2026-11-02 10:05:00","accountNumber":"0123456789","subAccount":"","code":"PS0123456789ABCDEF0123","content":"PS0123456789ABCDEF0123","transferType":"in","description":"test","transferAmount":399000,"accumulated":0,"referenceCode":"FT0001"}'; sig=$(printf '%s.%s' "$ts" "$body" | openssl dgst -sha256 -hmac "$SEPAY_WEBHOOK_SECRET" -hex | sed 's/^.* //'); curl -s -X POST http://localhost:8080/api/v1/webhooks/subscriptions/sepay -H "Content-Type: application/json" -H "X-SePay-Timestamp: $ts" -H "X-SePay-Signature: sha256=$sig" --data "$body"
```

- **Cách 2, qua SePay test mode:** mở tunnel HTTPS (ngrok hoặc cloudflared) tới cổng 8080, tạo webhook test mode trỏ tới `https://<tunnel>/api/v1/webhooks/subscriptions/sepay` với HMAC và "chỉ tiền vào", rồi dùng chức năng giả lập giao dịch với nội dung là `referenceCode` của attempt.

## 11. Các bước hiện thực (theo thứ tự)

1. **Chốt D1–D5.** Trên SePay test mode, xác nhận payload thật: trường nào chứa tài khoản/VA nhận, `code` khi nội dung đúng `PS…`, và định dạng `transactionDate`.
2. **Domain:** `SubscriptionPaymentEvent`, `PaymentEventState`, `ReviewReason`, repository event; bổ sung method trạng thái cho `SubscriptionPurchase` (map thêm cột `activation_*`) và `SubscriptionPaymentAttempt`; query mới cho repository attempt/purchase.
3. **Ingress:** properties, `SepayWebhookVerifier`, `SepayWebhookAuthenticationFilter` + `SecurityConfig`, `SepayWebhookPayloadMapper`, `PaymentInboxService`, `SepayWebhookController`. Có thể merge riêng: lúc này giao dịch đã được lưu bền vững dù chưa đối chiếu.
4. **Settlement:** `PaymentReferenceExtractor`, `PlatformAccountRegistry` + adapter SePay, `SettlementPolicy`, `PaymentSettlementService`, `PaymentEventClaimService`, `PaymentEventWorkerJob`.
5. **Kích hoạt:** port + `SubscriptionPeriodCalculator` + `PurchasedSubscriptionActivationService` trong module subscription; `PurchaseActivationService`, `PurchaseActivationJob`, gọi kích hoạt ngay sau PAID.
6. **Test** (mục 12), sau đó chạy toàn bộ Maven với biến `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` để cả 5 class cashbook/report cũng chạy.
7. **Docs:** OpenAPI đổi operation webhook sang IMPLEMENTED (thêm 413), cập nhật thiết kế, FE sync (polling, ẩn QR), test plan, README; viết `subscription-purchase-phase4-implementation.md` kèm kết quả test thật.

## 12. Kiểm thử

**Unit (thuần, không Spring)**

- `SepayWebhookVerifier`: chữ ký đúng; sai một ký tự; thiếu tiền tố; sửa một byte body; timestamp lệch +301/−301 giây; timestamp không phải số; secret trống.
- `SepayWebhookPayloadMapper`: `transactionDate` giờ Việt Nam → UTC; `code` null; `referenceCode` rỗng → định danh `WEBHOOK:<id>`; `transferAmount` ≤ 0 hoặc không nguyên → lỗi.
- `PaymentReferenceExtractor`: mã đứng riêng, có chữ thường, dính chữ khác (`XPS…`), hai mã khác nhau, `code` lệch `content`, mã định dạng V41 cũ 24 HEX.
- `SettlementPolicy`: bảng quyết định mục 6, mỗi dòng một case, cộng biên cửa sổ thời gian.
- `SubscriptionPeriodCalculator`: 31/01 + 1 tháng → cuối tháng 2 (năm nhuận và không nhuận), 12 tháng, theo múi giờ `Asia/Ho_Chi_Minh` ở ranh giới nửa đêm UTC.

**Integration (PostgreSQL thật, schema cô lập, giống Phase3)**

| Case | Kỳ vọng |
|---|---|
| Webhook ký đúng | 200 `{"success":true}`, 1 event `RECEIVED`, 1 audit |
| Gửi lại cùng `id` | 200, vẫn 1 event, `delivery_count = 2`, không thêm audit |
| Chữ ký sai / timestamp cũ / body > 64 KiB | 401 / 401 / 413, không có dòng nào |
| DB lỗi lúc lưu (spy ném lỗi) | 503, không có dòng nào |
| Luồng đầy đủ: purchase → attempt → webhook → worker | attempt `SUCCESS`, purchase `PAID` rồi `ACTIVATED`, subscription ACTIVE đúng kỳ và snapshot từ quote, `/me/entitlements` có hiệu lực |
| Sai số tiền | event + purchase `REVIEW_REQUIRED AMOUNT_MISMATCH`, attempt PENDING bị hủy |
| Không có mã / mã không tồn tại | event `REVIEW_REQUIRED REFERENCE_UNMATCHED`, không link |
| Chuyển sau khi hủy purchase | `PURCHASE_CANCELLED`, purchase giữ `CANCELLED` |
| Webhook đến muộn, tiền chuyển đúng hạn, purchase đã `EXPIRED` | `PAID` rồi `ACTIVATED` |
| Chuyển khoản thứ hai (khác `id`) | `DUPLICATE_PAYMENT`, subscription không bị kéo dài |
| Admin đã tạo ACTIVE trước khi kích hoạt | purchase `REVIEW_REQUIRED SUBSCRIPTION_CONFLICT`, giữ `paidAt` |
| Kích hoạt lỗi kỹ thuật (spy audit ném lỗi) | purchase vẫn `PAID`, đã lên lịch retry; lần sau thành công, chỉ 1 subscription |
| Lease hết hạn (giả lập restart) | event quay về `RETRY_PENDING` rồi xử lý xong, không xử lý hai lần |
| Hai worker chạy song song | mỗi event chỉ được settle một lần |
| Cancel và settlement tranh nhau | cancel thắng → review `PURCHASE_CANCELLED`; settlement thắng → cancel trả 409 |

**Thủ công:** cả hai cách ở mục 10, và một lần chuyển khoản thật số tiền nhỏ với gói giá thấp trước khi bật production.

## 13. Rủi ro

- **Payload test mode khác live:** viết mapper chịu được field mới (`additionalProperties: true` trong OpenAPI), kiểm lại trên live bằng một giao dịch nhỏ.
- **Secret lộ hoặc mất:** chỉ để trong biến môi trường/secret store; đổi secret trên SePay và cấu hình cùng lúc. Có thể cho phép hai secret song song trong lúc chuyển đổi nếu cần.
- **Webhook thất lạc quá 33 phút retry:** Phase4 chưa tự phục hồi được; cần Phase5 (đối soát qua API SePay) hoặc gửi lại thủ công từ dashboard SePay. Ghi rõ trong runbook trước khi bật production.
- **Review tồn đọng:** chưa có API admin nên phải xử lý bằng SQL. Nên làm Phase5 trước khi mở bán thật.
- **Tiền thật:** giữ `subscription-purchase.accept-new=false` trên production cho đến khi luồng đầy đủ và các case review đã được test thật.
