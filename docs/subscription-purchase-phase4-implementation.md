# Giai đoạn 4 — Webhook SePay, ghi nhận thanh toán và kích hoạt gói

Ngày hiện thực: 2026-11-02. Theo [plan Phase4](subscription-purchase-phase4-plan.md) với khuyến nghị D1–D5. Không có migration mới (dùng schema V41/V42). Phase5 (job đối soát API SePay, API admin review) vẫn PLANNED, nên **chỉ bật `subscription-purchase.accept-new=true` trên production sau khi đã test thật với SePay** và có quy trình xử lý review bằng SQL.

## Luồng

```text
SePay ──POST /api/v1/webhooks/subscriptions/sepay──▶ SepayWebhookAuthenticationFilter (≤64 KiB, HMAC, ±5 phút)
      ◀── 200 {"success":true} sau khi commit ───── SepayWebhookController → PaymentInboxService (event RECEIVED)
PaymentEventWorkerJob (5s) → PaymentEventProcessor
   claim (lease 60s, SKIP LOCKED) → SubscriptionPaymentSettlementService ── SettlementPolicy (thuần)
      PAY    → attempt SUCCESS, purchase PAID, event PROCESSED ─▶ PurchaseActivationService.activateNow
      REVIEW → event REVIEW_REQUIRED (+ purchase REVIEW_REQUIRED nếu chưa trả đủ: sai tiền/sai tài khoản)
      lỗi kỹ thuật → RETRY_PENDING (5s, 30s, 2m, 10m, 30m…), quá 24h → REVIEW_REQUIRED TECHNICAL_RETRY_EXHAUSTED
PurchaseActivationService ──port PurchasedSubscriptionActivation──▶ module subscription: ACTIVE + snapshot từ quote
   chặn nghiệp vụ → purchase + event REVIEW_REQUIRED (giữ paidAt); lỗi kỹ thuật → PurchaseActivationJob (10s) retry
```

Thứ tự khóa: restaurant → purchase → attempts → subscription → event, đúng thiết kế §8.

## Kiến trúc

| Lớp | Class | Trách nhiệm |
|---|---|---|
| Adapter SePay | `SepayWebhookAuthenticationFilter`, `SepayWebhookVerifier`, `SepayWebhookProperties` | Giới hạn body, xác minh HMAC trên đúng raw bytes, đặt authority `SEPAY_WEBHOOK` |
| Adapter SePay | `SepayWebhookPayloadMapper`, `SepayWebhookController`, `SepayPlatformAccountRegistry` | Chuẩn hóa payload thành `VerifiedPaymentEvent`; ACK; tài khoản nhận tin cậy |
| Port | `VerifiedPaymentEvent`, `PlatformAccountRegistry` (module purchase); `PurchasedSubscriptionActivation` (module subscription) | Ranh giới thay thế, không phụ thuộc SePay |
| Inbox | `PaymentInboxService` | Lưu bền vững, chống trùng theo `id`/bank reference, đếm `delivery_count` |
| Đối chiếu | `PaymentReferenceExtractor`, `SettlementPolicy`, `SubscriptionPaymentSettlementService` | Tách mã, ra quyết định thuần, khóa + áp dụng + audit |
| Worker | `PaymentEventClaimService`, `PaymentEventProcessor`, `PaymentEventWorkerJob`, `PaymentWorkerProperties` | Claim/lease/recover/backoff |
| Kích hoạt | `PurchaseActivationService`, `PurchaseActivationJob`; `PurchasedSubscriptionActivationService`, `SubscriptionPeriodCalculator` | PAID → ACTIVATED; tạo subscription từ quote, kỳ theo tháng lịch của `billingZoneId` |
| Domain | `SubscriptionPaymentEvent`, `PaymentEventState`, `ReviewReason`; method trạng thái trên `SubscriptionPurchase`, `SubscriptionPaymentAttempt` | Chỉ ghi các cột trigger V41 cho phép |

## Điều chỉnh so với plan

- Service đối chiếu đổi tên thành `SubscriptionPaymentSettlementService` vì `PaymentSettlementService` đã là tên bean của module thanh toán hóa đơn món ăn.
- Cấu hình lệch giờ đặt ở `subscription-purchase.payment-clock-skew` (thuộc module, không thuộc SePay) thay cho `app.sepay.settlement.clock-skew`.
- Trùng định danh nhưng khác số tiền (`IDENTITY_CONFLICT`): chỉ log + audit `SUBSCRIPTION_PAYMENT_EVENT_IDENTITY_CONFLICT`, giữ nguyên event gốc. Trigger V41 không cho chuyển `RECEIVED → REVIEW_REQUIRED` trực tiếp; admin xử lý ở Phase5.
- Purchase chỉ bị chuyển sang `REVIEW_REQUIRED` khi còn `PENDING_PAYMENT`, chưa hết hạn và tiền sai số tiền hoặc sai tài khoản. Các trường hợp còn lại (sau hủy, ngoài thời hạn, chuyển lần hai) chỉ chuyển event sang review, purchase giữ trạng thái cũ để không chiếm slot mua mới.
- Không thêm cấu hình pool scheduler: dự án đã có bean `realtimeTaskScheduler` (2 luồng) và mọi `@Scheduled` dùng chung bean đó.

## Cấu hình

```properties
SEPAY_WEBHOOK_SECRET=<secret tạo trong wizard webhook SePay, chọn HMAC-SHA256>
# tùy chọn
SUBSCRIPTION_PAYMENT_CLOCK_SKEW=PT2M
SUBSCRIPTION_PAYMENT_EVENTS_FIXED_DELAY=PT5S
SUBSCRIPTION_PURCHASE_ACTIVATION_FIXED_DELAY=PT10S
```

Cùng với các biến SePay của Phase3 (`SEPAY_ENABLED`, `SEPAY_PROVIDER_ACCOUNT_KEY`, `SEPAY_ACCOUNT_NUMBER`…). Trên SePay: webhook URL `https://<domain hoặc tunnel>/api/v1/webhooks/subscriptions/sepay`, xác thực HMAC-SHA256, chỉ gửi giao dịch tiền vào, bật "tự động gửi lại khi server trả lỗi".

## Test local không cần SePay

Bash, với `SEPAY_WEBHOOK_SECRET` trùng giá trị backend đang dùng và `content` là `referenceCode` của attempt:

```bash
ts=$(date +%s); body='{"id":1001,"gateway":"MBBank","transactionDate":"2026-11-02 10:05:00","accountNumber":"0123456789","subAccount":"","code":null,"content":"PS0123456789ABCDEF0123","transferType":"in","description":"test","transferAmount":399000,"accumulated":0,"referenceCode":"FT0001"}'; sig=$(printf '%s.%s' "$ts" "$body" | openssl dgst -sha256 -hmac "$SEPAY_WEBHOOK_SECRET" -hex | sed 's/^.* //'); curl -s -X POST http://localhost:8080/api/v1/webhooks/subscriptions/sepay -H "Content-Type: application/json" -H "X-SePay-Timestamp: $ts" -H "X-SePay-Signature: sha256=$sig" --data "$body"
```

`transactionDate` (giờ Việt Nam) phải nằm trong `[attempt.createdAt − 2 phút, expiresAt + 2 phút)`, `transferAmount` đúng giá quote, `accountNumber` đúng `SEPAY_ACCOUNT_NUMBER`. Sau tối đa ~5 giây, `GET /subscription-purchases/{id}` trả `ACTIVATED`.

## Xác minh

- Unit: `SepayWebhookVerifierTest` (3), `SepayWebhookPayloadMapperTest` (9), `PaymentReferenceExtractorTest` (4), `SettlementPolicyTest` (5), `SubscriptionPeriodCalculatorTest` (3) — 24 đạt.
- Integration `SubscriptionPaymentSettlementIntegrationTest` (13, PostgreSQL thật, schema cô lập) — đạt: lưu một lần và đếm redelivery; chữ ký sai/timestamp cũ/Bearer/body quá lớn không lưu gì; DB lỗi trả 503; luồng đầy đủ PAID → ACTIVATED từ quote dù catalog đổi giá sau đó; sai số tiền; mã không tồn tại; tiền sau khi hủy; webhook muộn cho khoản chuyển đúng hạn; chuyển lần hai; subscription có sẵn chặn kích hoạt nhưng giữ `paidAt`; lỗi kỹ thuật lúc kích hoạt được retry; lease hết hạn sau crash; hai worker song song.
- Toàn bộ Maven (JDK24 `--release 21`, PostgreSQL local, có `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, `-Dspring.test.context.cache.maxSize=2`): **1451 tests, 1 failure, 0 error**. Lỗi duy nhất là `SubscriptionPurchaseIntegrationTest` (Phase2) vô tình đọc cấu hình SePay từ `.env` cục bộ; đã ghim `app.sepay.enabled=false` cho test đó, chạy lại **34/34 đạt**.
- OpenAPI parse bằng SnakeYAML: 8 operation IMPLEMENTED, 2 PLANNED (admin review Phase5).
- **NOT RUN:** webhook thật từ SePay test mode qua tunnel; chuyển khoản thật.
