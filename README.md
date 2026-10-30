# POS SaaS Backend

Staff realtime dùng chung native STOMP `/ws` cho kho, yêu cầu QR, đơn hàng và KDS.
Mutation vẫn qua REST; gửi đơn `SUBMIT` vẫn tạo và xác nhận trong một lần.
Xem [kết nối và quyền](docs/realtime-api.md), [ORDER/QR và đồng bộ FE](docs/order-realtime-api.md)
và [publication boundaries](docs/order-realtime-implementation.md). Không cần migration mới.

## Inventory

V30 triển khai Inventory Phase 1: warehouse/default warehouse, sparse current
balance, minimum threshold và derived low-stock query. `quantity` chỉ đọc; chưa có
API nhập/xuất/chuyển/điều chỉnh, stock transaction hoặc tự trừ theo order/công thức.
Warehouse/balance cần `INVENTORY_MANAGEMENT`; low-stock cần thêm
`LOW_STOCK_ALERT`. Permission ghi mới chỉ grant OWNER/SUPER_ADMIN. Xem
[Inventory API](docs/inventory-api.md) và [OpenAPI tenant](docs/openapi/tenant-api.yaml).

V31 triển khai Inventory Phase 2: chứng từ `RECEIPT`, `ISSUE`, `ADJUSTMENT`, chỉnh
DRAFT bằng optimistic locking, confirm/cancel idempotent, cập nhật balance atomic và
sổ `inventory_transactions` append-only. Xác nhận không cho tồn âm và rollback toàn
bộ nếu bất kỳ dòng nào lỗi. Chưa có transfer, kiểm kê, nhà cung cấp, đổi đơn vị, tự
trừ theo order/công thức hoặc hoàn tác chứng từ đã xác nhận.

## Operational reports

Tenant operational reporting is available under `/api/v1/reports`. It separates successful payment collections from completed-order sales, applies restaurant timezone/currency filters, and exposes only read-only report DTOs. See [Report API](docs/report-api.md), [OpenAPI](docs/openapi/report-api.yaml), and [frontend synchronization notes](docs/report-fe-sync.md).

V26: bổ sung QR_TABLE_ORDER và QR_STATIC_ORDER cho catalog BASIC/PRO/PREMIUM.
Chỉ thêm mapping còn thiếu; giữ nguyên package tùy chỉnh, giá, maxStaff, limits và snapshot
subscription đã có. Quyền QR vẫn lấy từ snapshot effective; subscription ACTIVE cũ không tự
nhận feature mới. Không sửa V1–V25 hoặc thay đổi contract API.

V25: QR gọi món tại bàn và mang đi dùng chung request PENDING → staff review/approve/reject.
Không cần phiên/PIN trước gửi; PENDING chưa tạo order/món/phiên, chưa gửi bếp hoặc tính bill.
TABLE được duyệt mới lazy-open/append vào serving order; RESTAURANT tạo TAKEAWAY/QR_STATIC
độc lập, không cần TABLE hoặc POS_QUICK_ORDER. Guest opaque token/digest, TTL/rate limits,
idempotency, review context hash và lifecycle/checkout scope được tách khỏi staff ORDER contract.
Feature phải có trong immutable effective snapshot; V26 bổ sung catalog gói chuẩn, không ghi lại snapshot cũ.
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
V11 từng bỏ QR ordering khỏi ba gói chuẩn; V26 bổ sung lại QR_TABLE_ORDER/QR_STATIC_ORDER
cho các lần capture snapshot sau migration, giữ nguyên feature rows/lịch sử.
Xem quy tắc chuyển tiếp và ví dụ request trong docs/admin-api.md.
V14 triển khai API nhóm món và món bán với Strategy tạo item, soft delete, version và audit.
Xem mục Menu trong docs/tenant-api.md và docs/openapi/tenant-api.yaml.
V36 tách `MENU_AVAILABILITY_UPDATE` cho `PATCH /api/v1/menu/items/{itemId}/availability`;
`MENU_UPDATE` không còn đủ cho thao tác này. OWNER/SUPER_ADMIN có mặc định; staff
cần được cấp riêng, không tự mở quyền sửa giá/tên món. Feature `MENU_MANAGEMENT`,
body/response và nghiệp vụ không đổi. Sau migrate cần refresh/login để JWT nhận
quyền mới; xem hướng dẫn rollout trong [Tenant API](docs/tenant-api.md).
V28 bổ sung danh mục nguyên liệu trên cùng bảng `items` (`item_type=INGREDIENT`),
Strategy riêng, soft delete/version/audit và permission INGREDIENT_*; truy cập cần
RECIPE_MANAGEMENT hoặc INVENTORY_MANAGEMENT trong snapshot. Đây chưa phải module kho:
[API/Postman](docs/ingredient-api.md) · [OpenAPI tenant](docs/openapi/tenant-api.yaml).
V29 bổ sung aggregate công thức với immutable business version, đúng một active
version/món, ingredient snapshot, audit và permission RECIPE_*; truy cập chỉ dùng
RECIPE_MANAGEMENT. Công thức chưa trừ kho, không nhúng vào response order/public menu:
[API/Postman](docs/recipe-api.md) · [OpenAPI tenant](docs/openapi/tenant-api.yaml).
Recipe cost estimate bổ sung `GET .../recipes/{recipeId}/cost-estimate`: snapshot
định lượng của đúng version + giá nguyên liệu tham chiếu hiện tại, batch/per-yield
cost, warning giá 0 và kiểm tra đơn vị. Yêu cầu RECIPE_VIEW + RECIPE_MANAGEMENT;
chỉ đọc, no-store, không cần migration/kho/tồn và không cập nhật costPrice hay ledger.
V32 chốt snapshot recipe/định lượng bất biến tại SUBMIT, confirm, gọi thêm và QR
approval; DRAFT/QR pending không capture, legacy không đoán recipe. API đọc riêng
cần ORDER_VIEW + RECIPE_VIEW và RECIPE_MANAGEMENT, không lộ qua order/public QR.
Chưa giữ chỗ hoặc trừ kho: [API/Postman và thiết kế](docs/order-recipe-snapshot-api.md).
Confirm RECEIPT nay cập nhật `INGREDIENT.costPrice` theo bình quân liên hoàn trên
tổng tồn các kho cùng tenant, cùng transaction với tồn/ledger/audit; retry không
tính lặp. Không đổi giá bán hay `MENU_ITEM.costPrice`; recipe estimate đọc giá mới.
Không cần migration; xem quy tắc giá 0, cập nhật version và giới hạn định giá tại
[Inventory API](docs/inventory-api.md).
V15 triển khai 21 API khu vực/bàn, QR quản trị và phiên xếp khách OPEN -> CANCELLED.
Yêu cầu TABLE_MANAGEMENT và TABLE_* tương ứng; occupancy suy ra từ phiên OPEN,
được bảo vệ bằng tenant lock, @Version và partial unique index. QR token không xuất
hiện trong response bàn thông thường hoặc audit. Xem mục Tables trong docs/tenant-api.md.
Public menu QR và menu chung nhà hàng đã có API đọc riêng; complete DINE_IN tự đóng phiên đủ điều kiện.
`/api/v1/table-sessions/{sessionId}/close` vẫn dùng được riêng và idempotent sau auto-close.
V16/V17 triển khai ORDER MVP và usage guard thực tế dùng chung restaurant lock
với hủy phiên. Từ V21 đã có ghi nhận payment thủ công; V22 có KDS tối thiểu,
V27 có báo cáo vận hành và V30 có nền tảng inventory; AI vẫn chưa có API nghiệp vụ.
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

Giai đoạn 5 dùng recipe snapshot để giữ chỗ khi gửi món, tiêu thụ khi bắt đầu thực
hiện và giải phóng khi hủy PENDING. Tài liệu:
[API và Postman](docs/order-inventory-allocation-api.md),
[thiết kế/migration](docs/order-inventory-allocation-implementation.md),
[đồng bộ FE](docs/order-inventory-allocation-fe-sync.md).

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
## Inventory Giai đoạn 6

Cảnh báo tồn thấp có lịch sử OPEN/ACKNOWLEDGED/RESOLVED, tự đồng bộ cùng transaction
nhập/xuất/consume/threshold/lifecycle. Điều kiện dùng tồn vật lý, không dùng available.
Xem [API và Postman](docs/low-stock-alert-api.md),
[thiết kế](docs/low-stock-alert-implementation.md) và
[đồng bộ FE](docs/low-stock-alert-fe-sync.md).

## Inventory Giai đoạn 7

Kiểm kê DRAFT/COUNTING/CONFIRMED/CANCELLED; snapshot khi start, không freeze kho.
Phát hiện snapshot stale bằng quantity + số ledger movement theo tenant/kho/nguyên
liệu, bảo vệ reservation hiện tại. Confirm sinh một ADJUSTMENT qua posting chung
hoặc không sinh document nếu khớp tất cả. Migration V35, quyền STOCK_AUDIT_MANAGE,
dùng lại INVENTORY_MANAGEMENT + STOCK_AUDIT, không tự sửa package/subscription.
Xem [API/Postman](docs/stock-audit-api.md), [thiết kế và test](docs/stock-audit-implementation.md),
[đồng bộ FE](docs/stock-audit-fe-sync.md). Full Maven test phải dùng DB/schema test riêng,
không chạy migrate schema public của database đang làm việc.


## Recipe application and reference cost — Phase 1

V37 adds recipeEnabled/costConfigured to the private catalogue and freezes recipe/source
decisions at order acceptance. Unknown reference cost is distinct from explicitly configured
zero. Existing snapshots, reservations and ledger remain authoritative after catalogue changes.
V37 introduced no historical monetary costing or profit reports; V38 extends it below.
See [API/Postman](docs/recipe-application-and-cost-config-api.md),
[design and verification](docs/recipe-application-and-cost-config-implementation.md),
and [FE integration](docs/recipe-application-and-cost-config-fe-sync.md).

## Historical order-item reference cost — Phase 2

V38 freezes versioned MANUAL/RECIPE reference cost at fresh acceptance in the
existing recipe snapshot header/usages. COMPLETE includes configured zero;
INCOMPLETE/UNAVAILABLE keep totals null. Legacy rows stay unpriced and are never
backfilled from today's catalogue. RECIPE_USE copies its frozen tracked usage
price; unknown/legacy stays null. This is reference cost, not guaranteed actual
COGS or net profit; a snapshot does not mean inventory has been consumed.

Read `/api/v1/orders/{orderId}/items/{orderItemId}/cost-snapshot` with ORDER_VIEW
and REPORT_DETAIL_VIEW plus ORDER_MANAGEMENT and DETAIL_REPORT. The no-store
summary exposes no ingredients and leaves recipe/public/KDS/realtime contracts
unchanged. See [API/Postman](docs/order-reference-cost-snapshot-api.md),
[design and migration](docs/order-reference-cost-snapshot-implementation.md), and
[FE integration](docs/order-reference-cost-snapshot-fe-sync.md).


## Cashbook

V39 bổ sung sổ thu chi từ payment SUCCESS và phiếu chi POSTED; tạo/void phiếu chi,
đối chiếu receipt, filter/timezone/currency và permission CASHBOOK riêng. Confirm
nhập kho không tự ghi chi; dòng tiền không phải lợi nhuận. Currency receipt mới
được chốt khi tạo, lịch sử cũ giữ UNKNOWN. Xem [API](docs/cashbook-api.md),
[OpenAPI](docs/openapi/cashbook-api.yaml), [FE sync](docs/cashbook-fe-sync.md).
