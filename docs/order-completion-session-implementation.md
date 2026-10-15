# Hoàn tất DINE_IN và đóng phiên cùng transaction

`POST /api/v1/orders/{orderId}/complete` giữ nguyên body `{"expectedVersion":9}` (version ORDER), tenant từ JWT, `200 OrderResponse` và `Cache-Control: no-store`. Không thêm trường seating/version session vào request hoặc response.

| Loại đơn | Permission | Feature | Thành công |
| --- | --- | --- | --- |
| DINE_IN | ORDER_COMPLETE + TABLE_CLOSE | ORDER_MANAGEMENT + TABLE_MANAGEMENT | Order COMPLETED và đúng order.tableSessionId CLOSED |
| TAKEAWAY | ORDER_COMPLETE | ORDER_MANAGEMENT | Chỉ order COMPLETED; không gọi TABLE port |

## Nghiệp vụ

Hoàn tất chỉ khi đơn đã gửi/xác nhận, không OPEN/CANCELLED, `paymentStatus=PAID`, `paidAmount=totalAmount>0`, có ít nhất một dòng khác CANCELLED và mọi dòng đó SERVED. Không tự thanh toán hoặc chuyển trạng thái món, không tính lại tiền/giá menu và không sửa snapshot, payment, QR, configuration hoặc transfer history.

Với DINE_IN, kiểm quyền/feature và đúng session thuộc tenant trước cả no-op. Lần hoàn tất đầu yêu cầu session OPEN; order chưa COMPLETED nhưng session CLOSED/CANCELLED bị từ chối. Flush order mới COMPLETED trước khi readiness query kiểm toàn bộ order của phiên. Chỉ đóng khi mỗi order liên quan đã COMPLETED/PAID/đủ tiền, hoặc CANCELLED/UNPAID/paid=0, và có ít nhất một order hoàn tất. Không lấy session mới nhất theo tableId; transfer giữ sessionId và closure diễn ra tại vị trí hiện tại. Bàn/khu vực inactive không ngăn đóng phiên hợp lệ; occupancy tiếp tục được suy ra từ OPEN session.

## Kiến trúc và transaction

`OrderCompletionService` điều phối một transaction và restaurant pessimistic lock theo thứ tự mutation hiện tại. TABLE-owned `OrderSessionClosureCommand` cung cấp kiểm tra trạng thái/đóng đúng session qua adapter `MANDATORY`. `TableSessionClosureOperation` được dùng chung với `TableSessionClosureService` của API close riêng, tái sử dụng readiness, audit và Clock timestamp precision microsecond. ORDER không sửa TableSession hoặc truy cập repository TABLE trực tiếp.

Một completion thực sự tăng order version/mutationSequence một lần, session version một lần, ghi đúng một ORDER_COMPLETED và một TABLE_SESSION_CLOSED. `closedBy` là actor, `closedAt >= openedAt`. Lỗi readiness/closure/persistence/audit rollback cả hai entity và audit. Không có REQUIRES_NEW hoặc xử lý đóng phiên sau commit. Strategy/CoR tạo, thêm và sửa món không đổi.

## Retry và phiên cũ

| State | Hành vi |
| --- | --- |
| TAKEAWAY COMPLETED | Trả hiện trạng, không mutation/audit |
| DINE_IN COMPLETED + session CLOSED | No-op sau guard; chấp nhận order version cũ, giữ timestamp/version |
| Legacy DINE_IN COMPLETED + session OPEN | Kiểm lại thanh toán/món của order và readiness toàn phiên; chỉ đóng phiên, không sửa completedAt/version/mutationSequence hoặc tạo lại ORDER_COMPLETED |
| Legacy OPEN session có order khác chưa kết thúc/chưa giải quyết tiền | TABLE_SESSION_NOT_READY_TO_CLOSE; không đổi dữ liệu |
| Order chưa COMPLETED nhưng session CLOSED, hoặc session CANCELLED | TABLE_SESSION_NOT_OPEN |

API close riêng vẫn tồn tại và no-op sau auto-close. Retry order cũ không ảnh hưởng phiên mới trên cùng bàn. DINE_IN thiếu tableSessionId trả 409 INVALID_ORDER_SEATING; session không tồn tại/khác tenant trả TABLE_SESSION_NOT_FOUND. Các conflict khác dùng business code hiện có và HTTP 409.

## Migration và quyền

`V24__grant_order_completion_table_close.sql` chỉ thêm TABLE_CLOSE cho global system CASHIER/MANAGER bằng ON CONFLICT DO NOTHING. Không sửa V1–V23, custom role, GRANT/DENY, package hoặc feature snapshot. User DENY tiếp tục thắng; KITCHEN không được cấp ORDER_COMPLETE/TABLE_CLOSE. TABLE_CLOSE cũng cho phép API manual close/cancel khi thỏa business rule riêng. Login/refresh sau migration để JWT nhận grants.

## File thay đổi

- `src/main/java/com/possaas/modules/order/service/OrderCompletionService.java`
- `src/main/java/com/possaas/modules/order/service/OrderCompletionPolicy.java`
- `src/main/java/com/possaas/modules/table/application/port/OrderSessionClosureCommand.java`
- `src/main/java/com/possaas/modules/table/service/OrderSessionClosureCommandAdapter.java`
- `src/main/java/com/possaas/modules/table/service/TableSessionClosureOperation.java`
- `src/main/java/com/possaas/modules/table/service/TableSessionClosureService.java`
- `src/main/resources/db/migration/V24__grant_order_completion_table_close.sql`
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderBulkItemTransitionIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderCompletionSessionIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderCompletionServiceTest.java`
- `src/test/java/com/possaas/modules/order/OrderCompletionPolicyTest.java`
- `src/test/java/com/possaas/modules/order/OrderCompletionSessionOpenApiTest.java`
- `README.md`
- `docs/order-api.md`
- `docs/kitchen-completion-api.md`
- `docs/openapi/order-api.yaml`
- `docs/openapi/kitchen-lifecycle-api.yaml`
- `docs/tenant-api.md`
- `docs/openapi/tenant-api.yaml`
- `docs/order-completion-session-fe-sync.md`
- `docs/order-completion-session-implementation.md` (báo cáo này)

## Kiểm thử ngày 2026-10-14

Working copy: `C:/Users/Dell/OneDrive/Documents/New project/completion-close-be-work-20261014`. Java 21 và PostgreSQL 17.11 thực tế, schema test riêng được tạo và dọn đúng phạm vi.

| Kiểm tra | Kết quả |
| --- | --- |
| Baseline và Maven test với POM gốc sau sửa | Main compile thành công; testCompile bị chặn bởi MenuPolicyTest.java:18 thiếu costPrice trong ItemCreationContext, lỗi có sẵn |
| OrderCompletionPolicyTest | 13 test qua |
| OrderCompletionServiceTest | 14 test qua |
| OrderCompletionSessionIntegrationTest | 44 test PostgreSQL qua |
| OrderCompletionSessionOpenApiTest | 4 test qua |
| FlywayCleanMigrationIntegrationTest | 9 test qua, gồm schema rỗng tới V24 và V23→V24 bảo toàn lịch sử/override/custom role, SQL lặp idempotent |
| Toàn bộ suite chẩn đoán cuối | 557 test: 555 qua, 2 lỗi assertion MENU có sẵn, 0 errors, 0 skipped |

Coverage gồm paid/served/empty guards, real JWT/CASHIER/MANAGER/custom roles/DENY, conditional feature/permission trước no-op, legacy repair và chặn phiên có order khác, snapshot/payment không đổi, audit/persistence rollback sau flush, transfer/table reuse, mandatory transaction và concurrent complete/close/transfer/create.

Focused run đầu có một kỳ vọng mã lỗi transfer chưa đúng trong test mới; đã sửa theo contract INVALID_TABLE_SESSION_TRANSITION, và full run cuối qua toàn bộ 44 test integration mới. Các assertion cũ về complete DINE_IN không đóng phiên và CASHIER thiếu TABLE_CLOSE đã được cập nhật có chủ đích. Test V23 upgrade dừng ở target 23 để kiểm riêng tác động V23, không gộp grant V24.

Không sửa MENU hoặc POM repository. POM chẩn đoán trong working copy chỉ loại duy nhất `**/menu/MenuPolicyTest.java` khỏi test compilation, output vào `target/verification`. Hai assertion MENU còn lỗi: `createUsesStrategyAndUpdatePreservesLifecycleAndInternalDefaults` coi costPrice là field ẩn; `validationAndReadOnlyFieldsCannotBypassBusinessRules` coi costPrice chỉ đọc. Vì vậy đây không phải kết quả Maven suite chuẩn xanh.

Commands đã chạy trong working copy:

```powershell
$env:JAVA_HOME = 'D:\jdk java'
$env:PATH = $env:JAVA_HOME + '\bin;' + $env:PATH
mvn -o '-Dmaven.repo.local=C:\Users\Dell\.m2\repository' test
.\verification\run-tests.ps1 -LogName full-diagnostic-test.log
```

Script chẩn đoán sử dụng Maven `-o -f pom-verification.xml -Dspring.datasource.hikari.maximum-pool-size=5 -Dspring.test.context.cache.maxSize=1 -Ddebug=false -Dlogging.level.org.springframework=WARN test`, đặt DB_URL/Flyway/JPA vào schema test riêng. Không chạy Flyway clean trên database phát triển. Log nằm ở `baseline-standard-test.log`, `standard-test.log`, `completion-focused-test.log`, `full-diagnostic-test.log`; XML ở `target/verification/surefire-reports` của working copy.

## Đồng bộ FE và giới hạn

FE tiếp tục gọi complete với Order.version. Sau 200 DINE_IN, cập nhật OrderResponse và reload/invalidate danh sách bàn cùng đúng phiên, không cần request close thứ hai. 409 cần reload để người dùng kiểm tra; không tự giả lập CLOSED khi backend lỗi. Xem [hướng dẫn FE](order-completion-session-fe-sync.md) và [API/Postman](kitchen-completion-api.md#hoàn-tất-đơn-và-đóng-phiên-tự-động).

Task chỉ sửa backend/tài liệu; Flutter/Web chưa chỉnh. Payment, bulk ready/served vẫn không tự complete/close. Các giới hạn payment/refund/QR ordering/realtime/printing hiện có không đổi; lỗi test MENU còn nguyên để xử lý riêng.
