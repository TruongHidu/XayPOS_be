# Giai đoạn 3 — Payment attempt, mã chuyển khoản, QR SePay và thời hạn (PLAN)

Ngày lập: 2026-11-02. Trạng thái: **IMPLEMENTED** theo khuyến nghị D1–D4, xem [báo cáo hiện thực](subscription-purchase-phase3-implementation.md). Nền: Phase2 đã có purchase/onboarding và schema V41 (`subscription_payment_attempts` đã tồn tại, chưa có runtime). Tài liệu liên quan: [thiết kế](subscription-purchase-design.md), [OpenAPI](openapi/subscription-purchase-api.yaml), [test plan](subscription-purchase-test-plan.md).

## 1. Mục tiêu và phạm vi

**Trong phạm vi**

1. `POST /api/v1/subscription-purchases/{purchaseId}/payment-attempts` — tạo attempt PENDING kèm mã chuyển khoản duy nhất, hướng dẫn chuyển khoản và QR SePay/VietQR; idempotent theo `Idempotency-Key`.
2. `GET /api/v1/subscription-purchases/{purchaseId}/payment-attempts/current` — khôi phục QR/trạng thái sau reload, chỉ đọc.
3. Quy tắc thời hạn: attempt hết hạn đúng `purchase.expiresAt`, project khi GET, expire dưới lock khi command, và job định kỳ expire purchase/attempt quá hạn.
4. Cấu hình tài khoản nhận tiền của nền tảng + cấu hình mã thanh toán trên SePay.

**Ngoài phạm vi (Phase4–5):** webhook SePay, HMAC verifier, inbox event, settlement PAID, activation, đối soát API, admin review. Vì chưa có webhook, `subscription-purchase.accept-new` **giữ false trên production** sau Phase3.

## 2. Quyết định cần chốt trước khi code

| # | Vấn đề | Khuyến nghị |
|---|---|---|
| D1 | Độ dài mã chuyển khoản. V41 ép `^PS[0-9A-F]{24}$` (26 ký tự). Trường *Purpose of Transaction* (tag 62-08) của EMVCo/VietQR giới hạn **25 ký tự**, nên mã 26 ký tự có thể bị app ngân hàng cắt khi quét QR. SePay cho hậu tố 1–30 ký tự, nên phía SePay không bị chặn. | Migration **V42** đổi CHECK thành `^PS[0-9A-F]{20}$` (22 ký tự, 80 bit ngẫu nhiên, còn dư so với giới hạn 25). Bảng attempt chưa có dữ liệu runtime nên đổi an toàn. Nếu không muốn thêm migration thì phải kiểm thử quét QR 26 ký tự trên app của các ngân hàng chính trước. |
| D2 | Dạng QR trả FE | Backend sinh **cả hai**, đều offline (không gọi HTTP): `qrPayload` (chuỗi EMVCo VietQR, FE tự render) và `qrImageUrl` (`https://qr.sepay.vn/img?acc=..&bank=..&amount=..&des=..`, FE hiển thị bằng `<img>`). Thêm `qrImageUrl` (nullable) vào schema `PaymentInstructions`. |
| D3 | Job expire định kỳ (thiết kế gốc đặt ở Phase5) | Đưa lên Phase3 vì đã có `PurchaseExpirationService.expireIfDue`, chỉ cần thêm batch và job. |
| D4 | Cửa sổ tối thiểu còn lại khi tạo attempt | Giữ đúng contract: chỉ cần `now < expiresAt`. FE hiển thị đếm ngược theo `serverTime`/`expiresAt`. Không thêm rule mới. |

## 3. Kiến trúc và áp dụng SOLID

Giữ modular monolith: Controller → Service → Repository → Entity/DTO, giống Phase2.

```text
modules/subscriptionpurchase/
  application/port/
    PaymentInstructionProvider        (port: chuẩn bị hướng dẫn thanh toán; chỉ một trách nhiệm)
    PaymentInstructionRequest         (record: referenceCode, amount, currencyCode, expiresAt)
    PreparedPaymentInstructions       (record: provider, providerAccountKey, PaymentInstructions)
    PaymentProviderUnavailableException
  controller/
    PurchasePaymentAttemptController  (POST tạo + GET current)
  dto/
    PaymentInstructions               (bankCode, accountNumber, accountName, transferContent, qrPayload, qrImageUrl)
    PaymentAttemptResponses           (View, Current{paymentAttempt}, Creation{body, replayed})
    PurchaseRequests.CreateAttempt    (body {}; từ chối field lạ)
  entity/
    AttemptStatus                     (PENDING, SUCCESS, FAILED, EXPIRED, CANCELLED)
    SubscriptionPaymentAttempt        (Persistable<UUID>, UUID gán trước, field tài chính không có setter)
  repository/
    SubscriptionPaymentAttemptRepository
  service/
    PaymentReferenceGenerator         (SecureRandom → PS + 20 HEX)
    PaymentAttemptCommandService      (orchestration tạo attempt)
    PaymentAttemptQueryService        (GET current, chỉ đọc)
    PaymentAttemptResponseMapper      (view / initial / freeze / replay)
    PurchaseDbFailures                (tách từ PurchaseCommandService: sqlState, constraintName, retryable)
  jobs/
    PurchaseExpirationJob
infrastructure/payment/vietqr/
    VietQrPayloadBuilder              (EMVCo TLV + CRC16-CCITT; không phụ thuộc SePay)
infrastructure/subscriptionpayment/sepay/
    SepayProperties                   (@ConfigurationProperties "app.sepay")
    SepayPaymentInstructionProvider   (implements PaymentInstructionProvider)
```

| Nguyên tắc | Áp dụng cụ thể |
|---|---|
| **S** | Generator chỉ sinh mã. VietQR builder chỉ mã hóa EMVCo. Adapter SePay chỉ ghép cấu hình tài khoản với QR. Command service chỉ điều phối lock/transaction/idempotency. Mapper chỉ chuyển đổi DTO/JSON replay. Job chỉ lên lịch, logic expire nằm ở service. |
| **O** | Muốn đổi sang QR động qua HTTP hoặc provider khác thì thêm implementation mới của `PaymentInstructionProvider`, không sửa command service. Luồng "chuẩn bị ngoài lock, kiểm tra lại trong lock" đã sẵn cho adapter có gọi HTTP. |
| **L** | Mọi implementation của port phải: không ghi DB, không giữ lock, ném `PaymentProviderUnavailableException` khi không sẵn sàng, và trả `transferContent == referenceCode`. Viết một contract test dùng chung cho mọi implementation. |
| **I** | Port Phase3 chỉ có `isReady()` và `prepare()`. Xác minh webhook (Phase4) là interface riêng (`PaymentWebhookVerifier`), đối soát (Phase5) là interface khác; không gộp thành một "PaymentGateway" lớn. |
| **D** | `modules/subscriptionpurchase` chỉ phụ thuộc port. Package `infrastructure/...sepay` phụ thuộc port và module, không có chiều ngược lại. Test thay adapter bằng stub bean. |

Phase3 không tạo abstraction cho class không có ranh giới thay thế (đúng thiết kế §3): generator, mapper, VietQR builder là class cụ thể. `SecureRandom` được inject để test được.

## 4. Cấu hình

`application.yml` (giá trị thật để trong `.env`/secret store, không commit):

```yaml
app:
  sepay:
    enabled: ${SEPAY_ENABLED:false}
    provider-account-key: ${SEPAY_PROVIDER_ACCOUNT_KEY:}   # khóa nội bộ của tài khoản nền tảng, ví dụ "PLATFORM_MB_01"
    bank-code: ${SEPAY_BANK_CODE:}                         # mã ngắn theo qr.sepay.vn/banks.json, ví dụ MBBank
    bank-bin: ${SEPAY_BANK_BIN:}                           # 6 số NAPAS BIN, dùng cho EMVCo
    account-number: ${SEPAY_ACCOUNT_NUMBER:}
    account-name: ${SEPAY_ACCOUNT_NAME:}
    qr-image-base-url: https://qr.sepay.vn/img
    qr-template: compact
  jobs:
    subscription-purchase-expiration:
      enabled: true
      fixed-delay: PT1M
      initial-delay: PT1M
      batch-size: 100
```

`SepayProperties.isConfigured()` kiểm tra đủ field, giống `CloudinaryProperties`. Provider `isReady()` = `enabled && isConfigured()`. Thêm validate format: BIN 6 chữ số, số tài khoản chỉ gồm chữ số, tên tài khoản ASCII in hoa.

**Trên dashboard SePay (test mode trước, rồi Live):** cấu trúc mã thanh toán có tiền tố `PS`, hậu tố dài đúng 20 ký tự (min = max = 20, gồm chữ và số), liên kết đúng tài khoản ngân hàng nhận tiền. Nội dung chuyển khoản chỉ là mã, không thêm chữ, để ngân hàng ít làm sai lệch nội dung.

## 5. Mã chuyển khoản (reference)

- `PaymentReferenceGenerator.next()`: lấy 10 byte (80 bit) từ `SecureRandom`, chuyển sang HEX in hoa, thêm tiền tố `PS` → ví dụ `PS0123456789ABCDEF0123`.
- Không suy ra từ restaurantId hoặc số tăng dần, và không tái sử dụng mã (đã có UNIQUE toàn cục).
- Nếu trùng (`23505` trên constraint `reference_code`): sinh mã mới và chạy lại transaction, tối đa 3 lần, hết lượt trả `409 CONCURRENT_SUBSCRIPTION_UPDATE`.
- Mã chỉ để đối chiếu giao dịch, không phải bí mật. QR không chứa token.

## 6. Luồng tạo attempt (POST)

```text
1. access.require(CREATE)                                   — 401/403 trước replay
2. validate Idempotency-Key, body {}                        — 400 VALIDATION_ERROR / INVALID_REQUEST_BODY
3. hash = SHA-256('{"purchaseId":"<uuid>"}')
4. attempts.findByRestaurantIdAndIdempotencyKey → có: so hash → replay 200 / 409 IDEMPOTENCY_KEY_REUSED
5. ĐỌC định tuyến (không lock): purchase theo tenant → 404 PURCHASE_NOT_FOUND
6. NGOÀI transaction: reference = generator.next();
   prepared = provider.prepare(reference, amount, VND, expiresAt)  — lỗi → 503 PAYMENT_PROVIDER_UNAVAILABLE
7. commandTransaction (REQUIRES_NEW):
   a. access.lockRestaurant(tenant)
   b. kiểm tra lại key (replay nếu request song song đã thắng)
   c. !acceptNew → 503 PURCHASE_UNAVAILABLE
   d. purchases.findForUpdate(id, tenant)
   e. status: REVIEW_REQUIRED → 409 PURCHASE_REVIEW_REQUIRED; EXPIRED → 409 PURCHASE_PERIOD_EXPIRED;
      khác PENDING_PAYMENT → 409 PAYMENT_ATTEMPT_NOT_ALLOWED
   f. purchase.isDue(now) → expireIfDue(...) rồi trả kết quả EXPIRED (KHÔNG throw trong tx để commit expiry)
   g. kiểm tra lại prepared: amount/currency/expiresAt == purchase (khác → retry từ bước 5)
   h. attempts.lockPending(purchase) → còn PENDING → 409 PAYMENT_ATTEMPT_PENDING
   i. insert attempt (status PENDING, expiresAt = purchase.expiresAt, instructions, creation_response) + flush
   j. audit SUBSCRIPTION_PAYMENT_ATTEMPT_CREATED {purchaseId, referenceCode, amount, currencyCode, expiresAt}
8. Sau commit: kết quả EXPIRED → 409 PURCHASE_PERIOD_EXPIRED; còn lại → 201, Idempotency-Replayed:false
9. Bắt 23505 theo tên constraint:
   uq_spa_idempotency → đọc winner rồi replay | uq_spa_pending → 409 PAYMENT_ATTEMPT_PENDING
   reference_code     → sinh mã mới, retry   | 40001/40P01/55P03 → retry tối đa 3 | khác → 409 CONCURRENT_SUBSCRIPTION_UPDATE
```

Thứ tự lock tuân thiết kế §8: restaurant → purchase → attempts. Không có HTTP và không chuẩn bị provider khi đang giữ lock (bước 6 nằm ngoài transaction). Với adapter offline, bước 6 chỉ là tính toán thuần.

## 7. GET current

`PaymentAttemptQueryService.current(purchaseId)` chạy `@Transactional(readOnly = true)`:

- `access.require(VIEW)`; purchase theo tenant, không thấy → 404.
- Lấy attempt mới nhất (`created_at DESC, id DESC`, có index `ix_spa_history`), kể cả terminal; chưa có → `{"paymentAttempt": null}`.
- `displayStatus = EXPIRED` nếu `status == PENDING && !expiresAt.isAfter(now)`. Không ghi DB, không audit.
- FE chỉ render QR khi `displayStatus == PENDING` **và** purchase `displayStatus == PENDING_PAYMENT`. Mapper vẫn trả `instructions` immutable để đối chiếu/hỗ trợ.

## 8. Thời hạn

| Quy tắc | Cơ chế |
|---|---|
| `attempt.expiresAt == purchase.expiresAt`, không gia hạn | Gán từ purchase; FK `(purchase_id, restaurant_id, expires_at)` của V41 ép ở DB |
| Hợp lệ trong `[createdAt, expiresAt)`; `now == expiresAt` là hết hạn | `SubscriptionPaymentAttempt.isDue(now)` giống `SubscriptionPurchase.isDue` |
| GET chỉ project | Mapper tính `displayStatus` |
| Command thấy quá hạn thì expire và **commit** | Bước 7f; dùng lại `PurchaseExpirationService.expireIfDue` (đã chuyển attempt PENDING → EXPIRED) |
| Cancel purchase kết thúc attempt | Đã có ở Phase2 (`endPendingAttempts(..., "CANCELLED")`) |
| Job định kỳ | `PurchaseExpirationService.expireDueBatch()`: lấy ID ứng viên `status='PENDING_PAYMENT' AND expires_at <= now LIMIT batch` (index `ix_sp_expiry`, không lock) → mỗi purchase một transaction: lock restaurant (**không** yêu cầu ACTIVE, nhà hàng bị suspend vẫn phải expire) → `findForUpdate` → `expireIfDue`. Audit actor null. |
| Tiền về sau khi hết hạn | Phase4/5 xét theo `occurredAt`; Phase3 không làm gì thêm |

Job dùng `@ConditionalOnProperty` + `@Scheduled(fixedDelayString=...)` giống `SubscriptionExpirationJob`. Cần thêm `RestaurantRepository.findByIdForUpdate` dạng không lọc trạng thái, hoặc dùng bản hiện có rồi bỏ qua bước `requireActive`.

## 9. Migration V42 (theo D1)

```sql
ALTER TABLE subscription_payment_attempts DROP CONSTRAINT <tên CHECK reference_code>;
ALTER TABLE subscription_payment_attempts ADD CONSTRAINT ck_spa_reference_format
    CHECK (reference_code ~ '^PS[0-9A-F]{20}$');
```

Tên CHECK do PostgreSQL tự sinh (`subscription_payment_attempts_reference_code_check`), cần xác minh bằng `\d` trước. Preflight: dừng migration nếu bảng đã có dòng nào không khớp format mới, không xóa dữ liệu. Giữ `VARCHAR(40)`. Không sửa V41.

## 10. Lỗi

Dùng các code đã có trong OpenAPI: `PURCHASE_NOT_FOUND`, `PURCHASE_PERIOD_EXPIRED`, `PURCHASE_REVIEW_REQUIRED`, `PAYMENT_ATTEMPT_PENDING`, `PAYMENT_ATTEMPT_NOT_ALLOWED`, `IDEMPOTENCY_KEY_REUSED`, `CONCURRENT_SUBSCRIPTION_UPDATE`, `PURCHASE_UNAVAILABLE`, `PAYMENT_PROVIDER_UNAVAILABLE`. `PaymentProviderUnavailableException` được map ở service sang `BusinessException(503, ...)`; không thêm handler toàn cục mới.

## 11. Các bước hiện thực (theo thứ tự)

1. **Chốt D1–D4**; tạo cấu trúc mã thanh toán `PS` + 20 ký tự trên SePay test mode, mô phỏng một giao dịch có nội dung `PS…` và xác nhận SePay trích được mã.
2. **V42** + case trong `FlywayCleanMigrationIntegrationTest` (clean và upgrade V41→V42).
3. **Domain:** `AttemptStatus`, `SubscriptionPaymentAttempt`, `SubscriptionPaymentAttemptRepository`. Chuyển `lockPendingAttempts`/`endPendingAttempts` từ `SubscriptionPurchaseRepository` sang repository mới và cập nhật `PurchaseExpirationService` cùng `PurchaseCommandService.cancel`.
4. **Port + DTO:** `PaymentInstructionProvider` và các record, `PaymentInstructions`, `PaymentAttemptResponses`, `PurchaseRequests.CreateAttempt` (đăng ký trong `PurchaseRequestJsonConfiguration`).
5. **Infrastructure:** `VietQrPayloadBuilder`, `SepayProperties`, `SepayPaymentInstructionProvider`, khối `app.sepay` trong `application.yml`.
6. **Service:** `PaymentReferenceGenerator`, `PurchaseDbFailures` (tách helper từ `PurchaseCommandService`), `PaymentAttemptResponseMapper`, `PaymentAttemptCommandService`, `PaymentAttemptQueryService`.
7. **Controller:** `PurchasePaymentAttemptController` với `@PreAuthorize` như `OwnerPurchaseController`. Kiểm tra `PurchaseNoStoreFilter` đã phủ path mới (`no-store` + `Vary: Authorization`). Bổ sung `allowedActions` của purchase PENDING_PAYMENT: `CREATE_PAYMENT_ATTEMPT` (khi có quyền CREATE, flag bật, provider sẵn sàng) và `VIEW_PAYMENT_ATTEMPT`. Không sửa `creation_response` đã lưu.
8. **Expiry:** `expireDueBatch` + `PurchaseExpirationJob` + cấu hình job.
9. **Test** (mục 12), sau đó chạy toàn bộ Maven với `-Dspring.test.context.cache.maxSize=2` như Phase2.
10. **Docs:** đổi hai operation sang IMPLEMENTED trong OpenAPI, sửa pattern reference và thêm `qrImageUrl`; cập nhật thiết kế §7 (format reference), test plan (SP27–34), FE sync, API/Postman; ghi `subscription-purchase-phase3-implementation.md` kèm kết quả test thực tế.

## 12. Kiểm thử

**Unit**

- `VietQrPayloadBuilder`: khớp vector CRC16-CCITT đã biết, độ dài TLV đúng, amount là số nguyên, purpose ≤ 25 ký tự; payload đọc lại được bằng parser EMVCo trong test.
- `PaymentReferenceGenerator`: đúng format; dùng `SecureRandom` giả lập để ra kết quả xác định.
- `SepayPaymentInstructionProvider`: thiếu cấu hình → `PaymentProviderUnavailableException`; `qrImageUrl` được URL-encode; `transferContent == referenceCode`. Contract test dùng chung cho mọi implementation của port (L).
- `SubscriptionPaymentAttempt.isDue`: biên `now == expiresAt` và `expiresAt − 1µs`.

**Integration** (bổ sung vào `SubscriptionPurchaseIntegrationTest` hoặc tạo class attempt riêng)

| Case | Kiểm tra |
|---|---|
| SP27 | 201, một PENDING, amount/expiresAt/instructions khớp quote, có một audit |
| SP28 | Cùng key → 200 trả body gốc, kể cả sau khi attempt đã EXPIRED |
| SP29 | Key mới khi đang PENDING → 409; hai request song song khác key → một 201 + một 409 |
| SP30 | Dùng key của purchase1 cho purchase2 → 409 IDEMPOTENCY_KEY_REUSED |
| SP31 | `now == expiresAt` → 409 PURCHASE_PERIOD_EXPIRED và purchase/attempt đã EXPIRED được commit; GET chỉ project |
| SP32 | GET khi chưa có attempt → `null`; GET khi attempt terminal → trả attempt đó; không ghi DB |
| SP33 | Stub provider ném lỗi → 503, không có dòng attempt; gọi lại cùng key khi provider sẵn sàng → 201 |
| SP34 | Cancel purchase chuyển attempt PENDING → CANCELLED |
| SP66 | Insert SQL trực tiếp: hai PENDING, reference sai format, expiresAt lệch purchase → DB từ chối |
| Mới | Reference trùng (generator stub trả mã trùng một lần) → retry thành công |
| Mới | Provider được gọi khi chưa có transaction (stub ghi lại `TransactionSynchronizationManager.isActualTransactionActive()`) |
| Mới | Job expire purchase của nhà hàng bị suspend; chạy lại job lần hai không tạo thêm audit |
| Mới | Tenant khác → 404; staff có SUBSCRIPTION_VIEW → 403; flag tắt → 503 PURCHASE_UNAVAILABLE nhưng GET vẫn chạy |

**Sandbox thủ công:** dùng app ngân hàng quét QR (cả `qrPayload` render và `qrImageUrl`) để xác nhận tài khoản, số tiền và nội dung điền sẵn đúng, nội dung không bị cắt. Gửi giao dịch giả lập trên SePay test mode và kiểm tra trường `code` trong payload là mã `PS…` (dữ liệu đầu vào cho Phase4).

## 13. Rủi ro

- Ngân hàng tự thêm hoặc đổi nội dung chuyển khoản: chỉ dùng mã trần, chữ in hoa, không ký tự đặc biệt; Phase4 trích mã theo ranh giới token.
- Khách tự gõ nội dung thay vì quét QR: hiển thị nút copy nội dung; sai mã thì Phase4 đưa vào review `REFERENCE_UNMATCHED`.
- QR đã hết hạn nhưng ngân hàng vẫn nhận tiền: việc này thuộc Phase4/5 (review `PAYMENT_OUTSIDE_WINDOW`); FE ẩn QR khi `displayStatus` ≠ PENDING.
- Đổi tài khoản nhận tiền trong cấu hình: attempt cũ giữ `instructions`/`providerAccountKey` immutable; Phase4 đối chiếu theo `provider_account_key` đã lưu trên attempt.
- Bật `accept-new` trên production khi chưa có webhook sẽ nhận tiền mà không kích hoạt gói: chỉ bật ở local/sandbox cho đến khi xong Phase4.

Nguồn SePay đã đối chiếu: [cấu hình mã thanh toán](https://developer.sepay.vn/vi/sepay-webhooks/cau-hinh-ma-thanh-toan), [tạo QR và form thanh toán](https://developer.sepay.vn/vi/sepay-webhooks/tao-qr-va-form-thanh-toan), [test mode](https://developer.sepay.vn/vi/tien-ich-khac/test-mode/cau-hinh).
