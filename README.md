# POS SaaS Backend

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

## API Phase 1

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
