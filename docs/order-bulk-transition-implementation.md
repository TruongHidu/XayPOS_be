# Bulk order item transitions

Hai API bổ sung cho DINE_IN và TAKEAWAY:

| Endpoint POST | Permission hiệu lực | Feature snapshot |
| --- | --- | --- |
| `/api/v1/orders/{orderId}/mark-ready` | `KITCHEN_MARK_READY` | `KITCHEN_DISPLAY` |
| `/api/v1/orders/{orderId}/mark-served` | `ORDER_MARK_SERVED` | `ORDER_MANAGEMENT` |

Body dùng DTO hiện có `OrderItemTransitionRequest`: `{"expectedOrderVersion":7}`. Thành công trả `200 OrderResponse`, `Cache-Control: no-store`; tenant lấy từ JWT. Tài liệu header/body/response, lỗi và Postman nằm trong [kitchen-completion-api.md](kitchen-completion-api.md), OpenAPI trong [kitchen-lifecycle-api.yaml](openapi/kitchen-lifecycle-api.yaml).

## Nghiệp vụ và kiến trúc

Controller gọi `OrderBulkItemTransitionService`; `OrderBulkItemTransitionPolicy` kiểm tra toàn bộ danh sách và chọn những dòng cần đổi, không truy cập persistence hoặc tự sửa entity. Service tái sử dụng `OrderItemTransitionPolicy`, `OrderLifecyclePolicy`, `OrderServingStatusPolicy`, `OrderHistory`, `OrderAudit`, mapper và Clock. Strategy/CoR tạo, thêm và sửa món không thay đổi.

Root phải đang CONFIRMED/PREPARING/READY/SERVED và có ít nhất một món khác CANCELLED. mark-ready từ chối nếu có PENDING; chỉ COOKING chuyển READY, READY/SERVED giữ nguyên. Bulk mark-served cho phép PENDING/COOKING/READY chuyển SERVED, giữ SERVED và bỏ qua CANCELLED để xác nhận món đã thực sự phục vụ khi không dùng màn bếp. Chỉ cần ORDER_MARK_SERVED + ORDER_MANAGEMENT, không cần KITCHEN_DISPLAY hoặc API bếp trước đó; áp dụng cho mọi actor có quyền hiệu lực, không giới hạn role CASHIER. OPEN/COMPLETED/CANCELLED trả `INVALID_ORDER_ITEM_TRANSITION`; không có món hiệu lực trả `EMPTY_ORDER`.

Mỗi command lấy pessimistic restaurant lock trước khi đọc order/items và giữ lock trong một transaction, cùng thứ tự các mutation hiện tại. Validation toàn bộ batch xảy ra trước mọi thay đổi. Nếu cần mutation thì kiểm expectedOrderVersion, cập nhật những dòng được chọn, tổng hợp root status và gọi `order.changed` đúng một lần. Flush root trước response để trả version mới. History batch dùng cùng mutationSequence cho từng dòng khác nhau, phù hợp unique `(order_item_id, change_sequence)`.

Một audit `ORDER_ITEMS_MARKED_READY` hoặc `ORDER_ITEMS_MARKED_SERVED` cho command có thay đổi; mỗi dòng thực sự đổi có một history. Service lưu trạng thái trước mutation và ghi đúng nguồn PENDING/COOKING/READY → SERVED theo từng dòng, không ghi tất cả như READY → SERVED hoặc tạo bước nấu giả. History/audit cùng transaction và rollback cùng order/items khi lỗi. Request đã đạt mục tiêu trả no-op sau kiểm tenant, permission, feature, root và toàn bộ dòng; chấp nhận version cũ, không tăng version/mutationSequence, không tạo audit/history. Khi còn dòng cần đổi thì version cũ trả `CONCURRENT_ORDER_UPDATE`. Sau append PENDING mới, retry mark-served bằng version cũ bị kiểm version chặn; mark-ready vẫn bị PENDING chặn trước bước kiểm version. Request cũ không tự áp dụng vào batch mới.

PAID vẫn được chuyển trạng thái món khi root chưa terminal. Các trường tiền, payment, quantity, snapshot tên/đơn vị/giá, customer, session và sentToKitchenAt giữ nguyên. mark-served không tự complete hoặc close session; complete vẫn yêu cầu thanh toán đủ, mọi món hiệu lực SERVED và đóng phiên DINE_IN trong cùng transaction. Giữ API từng món cho phục vụ từng phần với rule strict READY → SERVED; mark-ready không được nhảy PENDING → READY. Không thêm chế độ vận hành hoặc bulk start-cooking.

## Migration

`V23__grant_bulk_order_serving_permissions.sql` bổ sung các liên kết `KITCHEN_MARK_READY` và `ORDER_MARK_SERVED` cho global system CASHIER/KITCHEN với `ON CONFLICT DO NOTHING`. Không sửa V1–V22, schema order, custom tenant roles, GRANT/DENY hoặc feature snapshots. Không cấp thêm start-cooking, complete, payment hoặc TABLE_CLOSE. Đăng nhập/refresh để JWT nhận role grants mới; feature gate vẫn áp dụng.

Thay đổi nghiệp vụ bulk phục vụ trực tiếp ngày 2026-10-14 chỉ cập nhật policy/service, test và tài liệu; không cần migration, permission, DTO hoặc endpoint mới. FE bỏ điều kiện mọi dòng phải READY/SERVED cho nút bulk phục vụ: chỉ cần root đang phục vụ, đúng quyền/feature và có dòng PENDING/COOKING/READY; gửi Order.version mới nhất, reload khi 409. Không đổi API phục vụ từng món hoặc complete.

## File thay đổi

- `src/main/java/com/possaas/modules/order/controller/OrderCommandController.java`
- `src/main/java/com/possaas/modules/order/service/OrderBulkItemTransitionPolicy.java` (mới)
- `src/main/java/com/possaas/modules/order/service/OrderBulkItemTransitionService.java` (mới)
- `src/main/java/com/possaas/modules/order/service/OrderHistory.java`
- `src/main/resources/db/migration/V23__grant_bulk_order_serving_permissions.sql` (mới)
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderBulkItemTransitionPolicyTest.java` (mới)
- `src/test/java/com/possaas/modules/order/OrderBulkItemTransitionIntegrationTest.java` (mới)
- `src/test/java/com/possaas/modules/order/OrderBulkItemTransitionOpenApiTest.java` (mới)
- `docs/order-api.md`
- `docs/kitchen-completion-api.md`
- `docs/openapi/order-api.yaml`
- `docs/openapi/kitchen-lifecycle-api.yaml`
- `docs/order-bulk-transition-implementation.md` (mới)
- `README.md`

## Kiểm thử ngày 2026-10-13

Java 21 và PostgreSQL thực tế. Working copy: `C:/Users/Dell/OneDrive/Documents/New project/bulk-order-be-work-20261013`.

| Kiểm tra | Kết quả |
| --- | --- |
| Maven `test` với POM gốc | Bị chặn ở testCompile bởi `MenuPolicyTest.java:18` thiếu costPrice trong constructor ItemCreationContext, lỗi có sẵn |
| Bulk policy | 9 test qua |
| Bulk integration | 40 test qua |
| Bulk OpenAPI | 3 test qua |
| Flyway clean/upgrade | 8 test qua, gồm schema rỗng tới V23 và V22→V23 bảo toàn override/custom role, chạy SQL V23 lặp không sinh duplicate |
| Toàn bộ suite chẩn đoán | 481 test: 479 qua, 2 lỗi assertion MENU có sẵn, 0 errors, 0 skipped |

Hai assertion MENU có sẵn: `createUsesStrategyAndUpdatePreservesLifecycleAndInternalDefaults` vẫn coi costPrice là field không được trả; `validationAndReadOnlyFieldsCannotBypassBusinessRules` vẫn coi costPrice là field chỉ đọc. Source/test MENU và POM repository không sửa trong task này.

POM chẩn đoán `pom-verification.xml` chỉ tồn tại trong working copy, loại duy nhất `**/menu/MenuPolicyTest.java` khỏi test compilation và xuất vào `target/verification`. Đây không phải kết quả toàn bộ suite xanh. Lệnh tương đương trong working copy:

```powershell
$env:JAVA_HOME = 'D:\jdk java'
$env:PATH = $env:JAVA_HOME + '\bin;' + $env:PATH
mvn -o -f pom-verification.xml '-Dmaven.repo.local=C:\Users\Dell\.m2\repository' '-Dspring.datasource.hikari.maximum-pool-size=5' '-Dspring.test.context.cache.maxSize=1' '-Ddebug=false' '-Dlogging.level.org.springframework=WARN' test
```

Run toàn bộ suite đã dùng schema PostgreSQL test riêng cho các context chung và dọn đúng schema đó sau khi xong; các integration module cũng tạo/dọn schema riêng theo fixture. Không chạy Flyway clean trên database phát triển. Log: `standard-test.log`, `bulk-focused-test.log`, `full-diagnostic-test.log`; XML kết quả trong `target/verification/surefire-reports` của working copy.

Coverage mới gồm mixed/cancelled items, transition chặn toàn batch, terminal/empty/no-op, immutable snapshot và tiền, PAID/session độc lập, real JWT CASHIER/KITCHEN/custom roles, user DENY, permission/feature/security boundary, validation, rollback sau audit đã ghi, persistence conflict mapping và concurrent bulk/single/append/payment. Existing API từng món được kiểm tra trong fixture và regression ORDER.

Giới hạn hiện tại: chưa có bulk start-cooking; mark-ready không nhảy bước PENDING→READY và API từng món vẫn strict. Chỉ bulk mark-served được xác nhận trực tiếp PENDING/COOKING/READY→SERVED. Không thêm payment/realtime/in phiếu. Kết quả kiểm thử ngày 2026-10-13 ở trên là lịch sử trước thay đổi direct serving; lỗi test MENU được để xử lý riêng.

## Kiểm tra direct serving (2026-10-14)

Java 21 và PostgreSQL trên schema test riêng, đã dọn sau khi chạy. Working copy: `C:/Users/Dell/OneDrive/Documents/New project/bulk-served-direct-be-work-20261014`.

- Mã production biên dịch thành công bằng POM gốc; Maven `clean test` vẫn bị chặn bởi lỗi constructor thiếu costPrice có sẵn ở `MenuPolicyTest.java:18`.
- POM chẩn đoán riêng loại duy nhất test bị lỗi biên dịch đó: 570 test chạy, 568 qua, 2 assertion MENU có sẵn vẫn lỗi; 0 errors, 0 skipped. Không sửa POM hoặc source/test MENU của repository.
- Cả 363 test ORDER/PAYMENT/TABLE/Flyway đều qua; trong đó bulk policy 10, bulk integration 51, bulk OpenAPI 4 và Flyway clean/upgrade 9.
- Coverage direct serving bổ sung nguồn history PENDING/COOKING/READY thực tế, cashier không cần quyền/feature bếp, stale version sau append, retry no-op, concurrency, rollback khi history/audit lỗi; single-item strict và yêu cầu thanh toán khi complete không đổi.

Log `standard-test.log`, `diagnostic-test.log` và XML `target/verification/surefire-reports` chỉ nằm trong working copy; không phải artifact triển khai.
