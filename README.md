# POS SaaS Backend

V25: QR gọi món tại bàn và mang đi dùng chung request PENDING → staff review/approve/reject.
Không cần phiên/PIN trước gửi; PENDING chưa tạo order/món/phiên, chưa gửi bếp hoặc tính bill.
TABLE được duyệt mới lazy-open/append vào serving order; RESTAURANT tạo TAKEAWAY/QR_STATIC
độc lập, không cần TABLE hoặc POS_QUICK_ORDER. Guest opaque token/digest, TTL/rate limits,
idempotency, review context hash và lifecycle/checkout scope được tách khỏi staff ORDER contract.
Feature phải có trong immutable effective snapshot; không tự grant QR ordering vào package chuẩn.
[API/Postman](docs/public-qr-order-api.md) · [OpenAPI](docs/openapi/public-qr-order-api.yaml)
· [Đồng bộ FE](docs/qr-order-fe-sync.md).
· [Triển khai và kết quả kiểm thử](docs/qr-order-implementation.md).

V24: POST `/api/v1/orders/{orderId}/complete` hoàn tất DINE_IN và đóng đúng
`order.tableSessionId` trong cùng transaction khi đã PAID/đủ tiền, mọi món chưa hủy
đều SERVED và toàn bộ đơn của phiên đủ điều kiện đóng. TAKEAWAY chỉ hoàn tất order.
Body vẫn `expectedVersion` của ORDER, response vẫn OrderResponse; không cần session
version hoặc gọi close HTTP thứ hai. Retry COMPLETED+CLOSED là no-op; legacy
COMPLETED+OPEN hợp lệ chỉ đóng phiên, giữ nguyên order version/timestamp/audit.
Grant TABLE_CLOSE cho global system CASHIER/MANAGER, bao gồm quyền manual close/cancel
theo business rule hiện có; user DENY và feature snapshot vẫn áp dụng. Login/refresh
để nhận quyền mới. [API/Postman](docs/kitchen-completion-api.md#hoàn-tất-đơn-và-đóng-phiên-tự-động)
· [Đồng bộ FE](docs/order-completion-session-fe-sync.md) · [OpenAPI](docs/openapi/kitchen-lifecycle-api.yaml).

V23: POST `/api/v1/orders/{orderId}/mark-ready` và `/mark-served` cập nhật món hàng loạt,
giữ luồng PENDING → COOKING → READY → SERVED. Cấp KITCHEN_MARK_READY và ORDER_MARK_SERVED
cho CASHIER/KITCHEN; feature snapshot và user DENY vẫn áp dụng. Body dùng expectedOrderVersion;
atomic, retry no-op không tạo history/audit mới, không tự complete hoặc close session.
[API/Postman và đồng bộ FE](docs/kitchen-completion-api.md#cập-nhật-tất-cả-món-theo-đơn) · [OpenAPI](docs/openapi/kitchen-lifecycle-api.yaml).

V22: KDS item queue/transition, gọi thêm khi order đang PREPARING/READY/SERVED nhưng chưa PAID,
ORDER_COMPLETE và đóng phiên bàn qua TABLE_CLOSE. PAID, SERVED, COMPLETED và CLOSED tách biệt.
[API/Postman](docs/kitchen-completion-api.md) · [OpenAPI](docs/openapi/kitchen-lifecycle-api.yaml).

V21: PAYMENT MVP ghi nhận đủ tiền CASH hoặc TRANSFER do nhân viên tự xác nhận; không tự hoàn tất order/đóng bàn, chưa tích hợp SePay. [API/Postman](docs/payment-api.md) · [OpenAPI](docs/openapi/payment-api.yaml).

V20: chuyển phiên OPEN sang bàn trống, giữ nguyên order/món, history append-only và
idempotent resource replay. Permission TABLE_TRANSFER + TABLE_MANAGEMENT;
default OWNER/MANAGER/WAITER. Refresh/login lại để JWT nhận quyền mới.
[API/Postman](docs/table-session-transfer-api.md) · [Đồng bộ FE](docs/table-session-transfer-fe-sync.md).

V19: tối đa một serving order/session (OPEN/CONFIRMED/PREPARING/READY/SERVED);
create/append/update-item có ba CoR riêng, giữ Strategy DINE_IN/TAKEAWAY và hash legacy.
GET /api/v1/table-sessions/{sessionId}/active-order hỗ trợ FE mở đúng đơn hiện tại.
Migration fail nếu legacy duplicates, không tự sửa lịch sử:
[SQL chẩn đoán](docs/order-serving-migration.md).

ORDER cho nhân viên: [API/Postman](docs/order-api.md) · [OpenAPI](docs/openapi/order-api.yaml) · [Đồng bộ app/web](docs/order-submit-fe-sync.md). DRAFT legacy hoặc SUBMIT tạo+xác nhận một bước; gọi thêm vào đơn đang phục vụ chưa PAID, snapshot giá, aggregate version, idempotency và usage guard phiên. QR ordering dùng request/approval APIs riêng nêu trên; không mở private ORDER API cho guest.

Menu chung nhà hàng/khách mang đi: [Public restaurant menu API](docs/public-restaurant-menu-api.md) · [OpenAPI](docs/openapi/public-restaurant-menu-api.yaml). Chỉ xem thực đơn, không cần bàn/phiên; quản lý link qua `/api/v1/restaurants/me/menu-link`.

Menu QR khách hàng (public, chỉ đọc): [API và Postman](docs/public-qr-menu-api.md) · [OpenAPI](docs/openapi/public-qr-menu-api.yaml).

Backend POS SaaS nhà hàng, sử dụng Java 21, Spring Boot 4, PostgreSQL, Flyway, JPA và JWT.

## Chạy local không cần Docker

Profile `dev` mặc định kết nối PostgreSQL local:

```text
jdbc:postgresql://localhost:5432/kiot_tay_db
username: ngocem1511
password: 12345
```

Có thể ghi đè bằng các biến môi trường `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Đảm bảo PostgreSQL đang chạy trước khi khởi động ứng dụng.

```powershell
$env:JAVA_HOME = 'D:\jdk java'
mvn spring-boot:run
```

Flyway tự chạy toàn bộ migration trong `src/main/resources/db/migration`; JPA dùng `ddl-auto: validate` để không tự ý thay đổi schema.

Gán package: chỉ tạo PENDING khi restaurant không có ACTIVE chưa hết hạn/PENDING. ACTIVE dùng
`change-package` sang package khác; PENDING phải activate hoặc cancel. List/detail restaurant có
`packageAssignmentState` để hỗ trợ UI. Chưa có renewal, self-service payment, billing hoặc payment gateway.
V8 thêm unique index cho PENDING; nếu dữ liệu cũ trùng, migration dừng để xử lý thủ công, không xóa lịch sử.
Xem chi tiết trong `docs/admin-api.md` và `docs/openapi/admin-api.yaml`.

V11 cho phép chọn feature và limits khi POST/PUT package. BASIC có STAFF_MANAGEMENT/TABLE_MANAGEMENT;
cả ba gói có QR_MENU_VIEW. Limits STAFF_MANAGEMENT rỗng của BASIC/PRO/PREMIUM nhận maxStaff 3/10/30;
limits tùy chỉnh không rỗng và snapshot ACTIVE cũ được giữ nguyên. V12 chuyển maxStaff từ feature
sang cột package và field top-level POST/PUT/GET. `maxStaff:null` hoặc bỏ qua là unlimited (kể cả PUT).
Snapshot mới phiên bản 2 lưu maxStaff cấp package; snapshot cũ vẫn giữ nguyên và đọc tương thích.
QR ordering không còn trong mapping ba gói chuẩn, feature rows/lịch sử
vẫn giữ. Xem quy tắc chuyển tiếp và ví dụ request trong docs/admin-api.md.
V14 triển khai API nhóm món và món bán với Strategy tạo item, soft delete, version và audit.
Xem mục Menu trong docs/tenant-api.md và docs/openapi/tenant-api.yaml.
V15 triển khai 21 API khu vực/bàn, QR quản trị và phiên xếp khách OPEN -> CANCELLED.
Yêu cầu TABLE_MANAGEMENT và TABLE_* tương ứng; occupancy suy ra từ phiên OPEN,
được bảo vệ bằng tenant lock, @Version và partial unique index. QR token không xuất
hiện trong response bàn thông thường hoặc audit. Xem mục Tables trong docs/tenant-api.md.
Public menu QR và menu chung nhà hàng đã có API đọc riêng; complete DINE_IN tự đóng phiên đủ điều kiện.
`/api/v1/table-sessions/{sessionId}/close` vẫn dùng được riêng và idempotent sau auto-close.
V16/V17 triển khai ORDER MVP và usage guard thực tế dùng chung restaurant lock
với hủy phiên. Từ V21 đã có ghi nhận payment thủ công; V22 có KDS tối thiểu, inventory/report/AI vẫn chưa có API nghiệp vụ.
V18 thêm ledger `order_item_submissions`: tenant-scoped key cho append SUBMIT, replay không thêm dòng lần hai.
Không gửi submissionMode vẫn DRAFT như cũ. SUBMIT tạo cần ORDER_CREATE + ORDER_UPDATE;
append cần ORDER_UPDATE và key/version cố định cho một intent. Không tự gộp order theo bàn.
V19 giới hạn tối đa một serving order/phiên. POST /api/v1/orders nay nhận optional
tableId + SUBMIT: backend dùng phiên OPEN hiện có hoặc mở phiên mới cùng transaction
với order CONFIRMED. Chỉ mở mới cần TABLE_OPEN; legacy tableSessionId và hash giữ nguyên.
Không thêm /orders/from-table; V21 bổ sung PAYMENT và V22 bổ sung KDS/close riêng. Xem docs/order-api.md và
docs/order-table-submit-implementation.md. Frontend handoff ở docs/order-submit-fe-sync.md.

V13 đưa phân quyền nhân viên, màn hình bếp, báo cáo chi tiết và in/in lại phiếu bếp xuống BASIC;
PRO có cùng bộ tính năng vận hành và thêm RECIPE_MANAGEMENT. maxStaff mặc định vẫn là BASIC=3,
PRO=10, PREMIUM=30. Giá và PREMIUM không đổi. Chỉ thêm mapping còn thiếu; giữ tùy chỉnh catalog
và snapshot đã kích hoạt. V13 không triển khai API công thức. FE đọc features/maxStaff từ API hiện có.

## API Phase 1

Tenant profile and staff administration are documented in [docs/tenant-api.md](docs/tenant-api.md)
and [docs/openapi/tenant-api.yaml](docs/openapi/tenant-api.yaml). V10 seeds tenant profile permissions
and basic MANAGER staff permissions. Staff operations require STAFF_MANAGEMENT; permission overrides
also require STAFF_PERMISSION. Disabled bearer accounts are rejected immediately; role/permission
claims already issued in access JWTs remain valid until their TTL expires. Security-sensitive staff
changes revoke refresh tokens. Existing users need refresh/login to receive newly seeded permissions.

Base URL: `http://localhost:8080/api/v1/auth`

- `POST /register-restaurant`: tạo nhà hàng và tài khoản OWNER.
- `POST /login`: đăng nhập, trả access token và refresh token.
- `POST /refresh`: đổi refresh token lấy cặp token mới.
- `POST /logout`: thu hồi refresh token hiện tại (Bearer token).
- `GET /me`: lấy người dùng, nhà hàng, vai trò và quyền hiệu lực (Bearer token).

Ví dụ đăng ký:

```json
{
  "restaurantCode": "DEMO01",
  "restaurantName": "Demo Restaurant",
  "ownerFullName": "Nguyen Van A",
  "ownerEmail": "owner@example.com",
  "ownerPhone": "0900000000",
  "password": "Secret123!"
}
```

JWT secret nằm ở `app.jwt.secret`; khi triển khai thật phải đặt `JWT_SECRET` riêng (tối thiểu 32 byte). CORS được cấu hình bằng `CORS_ALLOWED_ORIGINS`.

## Cấu trúc

Mã nguồn nằm dưới namespace `com.possaas`, được chia thành `common`, `config`, `modules`, `infrastructure` và `jobs`. Migration đặt tại `src/main/resources/db/migration`.

## Feature, package và subscription

Module `modules/subscription` cung cấp catalog package, vòng đời subscription và entitlement theo tenant. Chi tiết endpoint, request/response, policy bảo mật và ví dụ tích hợp nằm tại [docs/subscription-api.md](docs/subscription-api.md). OpenAPI tĩnh nằm tại [docs/openapi/subscription-api.yaml](docs/openapi/subscription-api.yaml).

Quyết định chính:

- Entitlement đọc từ snapshot JSONB đã chốt lúc activate; thay đổi mapping package sau đó không sửa quyền lợi lịch sử.
- Không dùng `features.is_active` làm global kill switch cho snapshot đã kích hoạt.
- Subscription hiệu lực khi `ACTIVE && startAt <= now && now < endAt`.
- Mỗi tenant chỉ có một dòng `ACTIVE`, bảo vệ bằng pessimistic tenant lock, `@Version` và partial unique index PostgreSQL.
- Downgrade chỉ thay đổi entitlement, không xóa dữ liệu nghiệp vụ.
- Audit subscription nằm cùng transaction; audit lỗi thì thay đổi subscription cũng rollback.

## SUPER_ADMIN

Backend quản trị hệ thống cung cấp dashboard, restaurant list/detail/status, subscription
list/detail/commands, audit log và feature/package catalog. Tài liệu đầy đủ:

- [docs/admin-api.md](docs/admin-api.md): hướng dẫn tích hợp, permission, request/response, side effect
  và error cases.
- [docs/openapi/admin-api.yaml](docs/openapi/admin-api.yaml): OpenAPI 3.1 canonical cho toàn bộ
  `/api/v1/admin/**`.

Mọi endpoint admin yêu cầu Bearer JWT của system `SUPER_ADMIN` (`restaurantId == null`) và permission
tương ứng; role hoặc permission riêng lẻ không đủ. Migration V7 thêm
`ADMIN_DASHBOARD_VIEW`, `RESTAURANT_VIEW`, `RESTAURANT_MANAGE`, `AUDIT_VIEW`, grant cho role
SUPER_ADMIN toàn hệ thống và thêm các index phục vụ restaurant/subscription/audit query. Admin đang
giữ token cũ cần login hoặc refresh sau migration để JWT nhận permission mới.

### Tạo tài khoản SUPER_ADMIN lần đầu

Bootstrap mặc định tắt và không chứa mật khẩu hard-code:

```powershell
$env:SUPER_ADMIN_BOOTSTRAP_ENABLED = 'true'
$env:SUPER_ADMIN_EMAIL = 'admin@kiottay.vn'
$env:SUPER_ADMIN_PASSWORD = 'ChangeThisStrongPassword!'
$env:SUPER_ADMIN_NAME = 'System Administrator'
mvn spring-boot:run
```

Bootstrap idempotent theo email. Sau khi tạo thành công, tắt `SUPER_ADMIN_BOOTSTRAP_ENABLED`, rồi
đăng nhập qua `POST /api/v1/auth/login`.

### Suspend tenant và expiration

- Chuyển restaurant từ `ACTIVE` sang `INACTIVE`/`SUSPENDED` revoke toàn bộ refresh token; access
  token cũ bị chặn ngay trên protected tenant request tiếp theo với `403 RESTAURANT_INACTIVE`.
- `SubscriptionExpirationService` là điểm chuyển duy nhất từ `ACTIVE` sang `EXPIRED`. Lazy
  reconciliation giải phóng active slot trước command; job batch chạy định kỳ và idempotent.
- Job cấu hình qua `SUBSCRIPTION_EXPIRATION_ENABLED`, `SUBSCRIPTION_EXPIRATION_FIXED_DELAY`,
  `SUBSCRIPTION_EXPIRATION_INITIAL_DELAY`, `SUBSCRIPTION_EXPIRATION_BATCH_SIZE`.
- `autoRenew=true` chưa tự gia hạn, tạo subscription hoặc thu tiền vì billing engine nằm ngoài phase
  hiện tại.
