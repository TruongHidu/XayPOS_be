# Giai đoạn 3 — Payment attempt, mã chuyển khoản và QR SePay

Ngày hiện thực: 2026-11-02. Theo [plan Phase3](subscription-purchase-phase3-plan.md) với khuyến nghị D1–D4. Webhook SePay, settlement PAID, activation và admin review vẫn PLANNED (Phase4–5), nên **giữ `subscription-purchase.accept-new=false` trên production**.

## API mới

| Method | Path | Quyền | Ghi chú |
|---|---|---|---|
| POST | `/api/v1/subscription-purchases/{purchaseId}/payment-attempts` | OWNER + SUBSCRIPTION_PURCHASE_CREATE | Body `{}`, header `Idempotency-Key` bắt buộc. 201 mới / 200 replay, `Idempotency-Replayed` |
| GET | `/api/v1/subscription-purchases/{purchaseId}/payment-attempts/current` | OWNER + SUBSCRIPTION_PURCHASE_VIEW | `{paymentAttempt: View \| null, serverTime}`; chỉ đọc |

Lỗi: 404 PURCHASE_NOT_FOUND; 409 PURCHASE_PERIOD_EXPIRED / PURCHASE_REVIEW_REQUIRED / PAYMENT_ATTEMPT_PENDING / PAYMENT_ATTEMPT_NOT_ALLOWED / IDEMPOTENCY_KEY_REUSED / CONCURRENT_SUBSCRIPTION_UPDATE; 503 PURCHASE_UNAVAILABLE (flag tắt) / PAYMENT_PROVIDER_UNAVAILABLE (SePay chưa cấu hình hoặc không chuẩn bị được QR). Purchase PENDING_PAYMENT có `CREATE_PAYMENT_ATTEMPT` trong `allowedActions` khi owner có quyền CREATE, flag bật và SePay sẵn sàng.

Ví dụ response 201 (dữ liệu DEMO):

```json
{
  "id": "…", "purchaseId": "…", "status": "PENDING", "displayStatus": "PENDING", "provider": "SEPAY",
  "referenceCode": "PS0123456789ABCDEF0123", "amount": 399000, "currencyCode": "VND",
  "expiresAt": "2026-11-02T03:15:00Z",
  "instructions": {
    "bankCode": "MBBank", "accountNumber": "0123456789", "accountName": "POS SAAS DEMO",
    "transferContent": "PS0123456789ABCDEF0123",
    "qrPayload": "000201010212…6304XXXX",
    "qrImageUrl": "https://qr.sepay.vn/img?acc=0123456789&bank=MBBank&amount=399000&des=PS0123456789ABCDEF0123&template=compact"
  },
  "paidAt": null, "createdAt": "2026-11-02T03:00:00Z", "updatedAt": "2026-11-02T03:00:00Z",
  "version": 0, "serverTime": "2026-11-02T03:00:00Z"
}
```

## Kiến trúc

| Lớp | Class | Trách nhiệm |
|---|---|---|
| Port | `application/port/PaymentInstructionProvider` (+ request/prepared records, `PaymentProviderUnavailableException`) | Chuẩn bị hướng dẫn thanh toán; không ghi DB, không lock, `transferContent == referenceCode` |
| Adapter | `infrastructure/subscriptionpayment/sepay/SepayPaymentInstructionProvider`, `SepayProperties` | Tài khoản nhận tiền của nền tảng; dựng QR offline, không gọi HTTP |
| Thư viện | `infrastructure/payment/vietqr/VietQrPayloadBuilder` | EMVCo/NAPAS TLV + CRC16-CCITT, không phụ thuộc SePay |
| Service | `PaymentReferenceGenerator` | `PS` + 20 HEX từ SecureRandom |
| Service | `PaymentAttemptCommandService` | Idempotency, chuẩn bị ngoài transaction, lock restaurant → purchase → attempts, revalidate, insert + audit |
| Service | `PaymentAttemptQueryService`, `PaymentAttemptResponseMapper` | GET current, project expiry; freeze/replay body gốc |
| Service | `PaymentAttemptAvailability` | Flag + SePay ready dùng chung cho command và `allowedActions` |
| Service | `PurchaseDbFailures` | Phân loại lỗi PostgreSQL (tách từ `PurchaseCommandService`) |
| Expiry | `PurchaseExpirationService.expireDueBatch`, `jobs/PurchaseExpirationJob` | Mỗi purchase quá hạn một transaction; nhà hàng suspend vẫn được expire |
| Data | `SubscriptionPaymentAttempt`, `AttemptStatus`, `SubscriptionPaymentAttemptRepository` | Entity bất biến tài chính; lock/end PENDING chuyển từ repository purchase sang đây |
| Migration | `V42__shorten_subscription_payment_reference.sql` | CHECK `^PS[0-9A-F]{20}$`; preflight dừng nếu có dữ liệu format cũ |

Thời hạn: `attempt.expiresAt = purchase.expiresAt` (FK V41 ép), hợp lệ trong `[createdAt, expiresAt)`. GET chỉ project `displayStatus=EXPIRED`. Command gặp purchase quá hạn sẽ expire và **commit** trước khi trả 409. Job `app.jobs.subscription-purchase-expiration` (mặc định 1 phút, batch 100) dọn phần còn lại. Cancel purchase chuyển attempt PENDING sang CANCELLED.

## Cấu hình

```powershell
$env:SUBSCRIPTION_PURCHASE_ACCEPT_NEW = 'true'          # chỉ local/sandbox
$env:SEPAY_ENABLED = 'true'
$env:SEPAY_PROVIDER_ACCOUNT_KEY = 'PLATFORM_MB_01'      # khóa nội bộ, không phải secret
$env:SEPAY_BANK_CODE = 'MBBank'                         # theo https://qr.sepay.vn/banks.json
$env:SEPAY_BANK_BIN = '970422'                          # NAPAS BIN 6 số
$env:SEPAY_ACCOUNT_NUMBER = '<số tài khoản nền tảng>'
$env:SEPAY_ACCOUNT_NAME = '<TEN CHU TAI KHOAN>'
.\mvnw.cmd spring-boot:run
```

Trên dashboard SePay: cấu trúc mã thanh toán có tiền tố `PS`, hậu tố dài đúng 20 ký tự (chữ và số), liên kết đúng tài khoản ngân hàng trên.

## Xác minh

- Unit: `VietQrPayloadBuilderTest` (6), `SepayPaymentInstructionProviderTest` (3), `PaymentReferenceGeneratorTest` (2) — đạt.
- Integration `SubscriptionPaymentAttemptIntegrationTest` (13, PostgreSQL thật, schema cô lập) — đạt: tạo/replay/PENDING trùng/key khác purchase/song song, biên hết hạn, GET null và 404 khác tenant, provider lỗi 503, provider gọi ngoài transaction, trùng reference retry, flag tắt, guard body/key/role, job expire nhà hàng suspend, DB từ chối format cũ.
- Toàn bộ Maven (`-Dspring.test.context.cache.maxSize=2`, JDK24 `--release 21`, PostgreSQL local): **1395 tests, failures 0, errors 5**. Cả 5 lỗi đều do 5 class cashbook/report yêu cầu đặt tường minh `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`; chạy lại riêng 5 class đó với biến môi trường đã đặt thì **24 tests đạt**, 0 lỗi.
- OpenAPI parse bằng SnakeYAML: 7 operation IMPLEMENTED, 3 PLANNED.
- **NOT RUN:** quét QR bằng app ngân hàng thật; SePay test mode trích mã `PS…`. Hai việc này cần tài khoản SePay của bạn.

## File thay đổi

- Mới: `V42__shorten_subscription_payment_reference.sql`; `application/port/*` (4 file); `dto/PaymentInstructions`, `dto/PaymentAttemptResponses`; `entity/AttemptStatus`, `entity/SubscriptionPaymentAttempt`; `repository/SubscriptionPaymentAttemptRepository`; `service/PaymentReferenceGenerator`, `PaymentAttemptAvailability`, `PurchaseDbFailures`, `PaymentAttemptResponseMapper`, `PaymentAttemptCommandService`, `PaymentAttemptQueryService`; `controller/PurchasePaymentAttemptController`; `jobs/PurchaseExpirationJob`; `infrastructure/payment/vietqr/VietQrPayloadBuilder`; `infrastructure/subscriptionpayment/sepay/SepayProperties`, `SepayPaymentInstructionProvider`; 4 file test mới.
- Sửa: `PurchaseRequests` (CreateAttempt), `PurchaseRequestJsonConfiguration`, `SubscriptionPurchaseRepository`, `PurchaseCommandService`, `PurchaseExpirationService`, `PurchaseResponseMapper`, `application.yml`, `src/test/resources/application.properties`, `FlywayCleanMigrationIntegrationTest`, `SubscriptionPurchaseIntegrationTest` (version 42, fixture reference 20 HEX), OpenAPI và các tài liệu subscription-purchase, README.
