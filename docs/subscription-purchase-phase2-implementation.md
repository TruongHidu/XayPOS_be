# Giai đoạn 2 — Purchase và onboarding

Ngày xác minh: 2026-11-01. Năm API đã IMPLEMENTED; QR/attempt API, provider webhook, settlement, activation từ purchase và admin payment review vẫn PLANNED.

## Phạm vi và nghiệp vụ

- Module subscriptionpurchase tách access/eligibility/quote/command/query/expiration/mapper. Owner JWT và persisted account/role/permissions được kiểm tra trước replay/query giá; không yêu cầu entitlement active.
- Create chỉ nhận packageCode; quote15phút bất biến, VND nguyên đồng dương, grants/maxStaff được chốt; maxStaff=null unlimited. Không tạo subscription PENDING hoặc cấp entitlement.
- Mỗi tenant một open purchase; same-key replay200 body lúc tạo, mới201; same-key khác normalized package409. Cancel idempotent không duplicate audit; GET expiry chỉ project, command/admin guard reconcile dưới lock.
- Port SubscriptionPurchaseConflictGuard do module subscription sở hữu, implementation thật ở module mới; admin create/activate/change-package chặn open purchase dù flag nhận mới tắt. Admin cancel giữ nguyên.
- Create dùng REPEATABLE_READ và bounded retry với transaction mới; restaurant→purchase→attempt→subscription→package. Admin MVCC touch restaurant giúp buyer chờ lock phải retry snapshot mới; existing updated_at trigger ghi nhận phối hợp thành công, không tạo profile audit giả.
- V41 tạo ba bảng, composite FK/DEFERRABLE circular evidence, unique open/pending/success/reference/transaction aliases, immutable financial snapshots và chống hard-delete. Seed OWNER VIEW/CREATE/CANCEL và system SUPER_ADMIN PAYMENT_REVIEW; không sửa V1–V40 hoặc gán quyền cho staff.

## Xác minh

- Toàn bộ Maven: **1376 tests, failures0, errors0, skipped0**,128 suites.
- Purchase integration: **34 passed**, Flyway clean/regression: **20 passed**; có V40→V41 upgrade giữ snapshot/permissions cũ.
- JDK24 với javac --release21; PostgreSQL17.11; schema test cô lập. Full-suite command dùng `-Dspring.test.context.cache.maxSize=2` để tránh giữ nhiều pool/context đồng thời. Lần không giới hạn cache gặp SQLSTATE53300; sau giới hạn cache đã chạy lại toàn bộ đạt. Không đổi pool/runtime/server connection limit và không skip test.
- Backend loopback/schema riêng: **10 HTTP samples** cho năm API và các nhánh replay/validation/auth/cancel; no-store/Vary kiểm tra thật. Không chuyển tiền thật.
- OpenAPI purchase/admin:327 internal ref occurrences,189 static examples hợp lệ;10 HTTP samples khớp schema; purchase contract đúng5 IMPLEMENTED +5 PLANNED.
- Test case Phase3–6 vẫn NOT RUN. Financial states trong test là fixture hợp lệ để kiểm tra read/replay/cancel/constraints, không phải provider/activation đã hoạt động.

## Vận hành và giới hạn

Default `subscription-purchase.accept-new=false`; local/test bật `SUBSCRIPTION_PURCHASE_ACCEPT_NEW=true` rồi khởi chạy backend. Đăng nhập/refresh token để nhận permission mới. READ/replay/cancel và admin guard không bị tắt bởi flag. [API/Postman](subscription-purchase-api.md), [thiết kế](subscription-purchase-design.md), [FE sync](subscription-purchase-fe-sync.md), [test plan](subscription-purchase-test-plan.md), [OpenAPI](openapi/subscription-purchase-api.yaml).

Source/migration được thêm vào repository; không tự chạy migration trên schema public/dev. Flyway áp dụng V41 khi backend khởi chạy theo cấu hình bình thường. Không sửa web/Flutter, không sửa target_old, không thêm provider dependency hoặc public security matcher. Phase3 tiếp theo: runtime attempt/QR trên schema đã có; chưa bật thanh toán production trước khi Phase4–5 đầy đủ.

## File thay đổi trong Giai đoạn 2

- `README.md`
- `docs/admin-api.md`
- `docs/openapi/admin-api.yaml`
- `docs/openapi/subscription-purchase-api.yaml`
- `docs/subscription-purchase-api.md`
- `docs/subscription-purchase-design.md`
- `docs/subscription-purchase-fe-sync.md`
- `docs/subscription-purchase-phase2-implementation.md`
- `docs/subscription-purchase-test-plan.md`
- `src/main/java/com/possaas/infrastructure/security/PurchaseNoStoreFilter.java`
- `src/main/java/com/possaas/modules/restaurant/repository/RestaurantRepository.java`
- `src/main/java/com/possaas/modules/subscription/application/port/SubscriptionPurchaseConflictGuard.java`
- `src/main/java/com/possaas/modules/subscription/repository/RestaurantSubscriptionRepository.java`
- `src/main/java/com/possaas/modules/subscription/service/SubscriptionCommandService.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/config/PurchaseProperties.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/config/PurchaseRequestJsonConfiguration.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/controller/OwnerPurchaseController.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/controller/SubscriptionOnboardingController.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/dto/PurchaseQuote.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/dto/PurchaseRequests.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/dto/PurchaseResponses.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/entity/PurchaseStatus.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/entity/SubscriptionPurchase.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/repository/SubscriptionPurchaseRepository.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/service/DatabaseSubscriptionPurchaseConflictGuard.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/service/PurchaseAccessPolicy.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/service/PurchaseCommandService.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/service/PurchaseEligibilityPolicy.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/service/PurchaseExpirationService.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/service/PurchaseQueryService.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/service/PurchaseQuoteFactory.java`
- `src/main/java/com/possaas/modules/subscriptionpurchase/service/PurchaseResponseMapper.java`
- `src/main/java/com/possaas/modules/user/service/TenantPermissionAssignmentPolicy.java`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V41__create_subscription_purchases.sql`
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java`
- `src/test/java/com/possaas/modules/subscription/service/SubscriptionCommandServiceClockTest.java`
- `src/test/java/com/possaas/modules/subscriptionpurchase/SubscriptionPurchaseIntegrationTest.java`
