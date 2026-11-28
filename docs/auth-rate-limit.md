# Giới hạn tần suất cho đăng nhập, đăng ký, làm mới phiên

Ba endpoint công khai `POST /api/v1/auth/login`, `/register-restaurant`, `/refresh` được bảo vệ bằng hai lớp:

| Lớp | Chặn | Khoá đếm | Chạy ở đâu |
|---|---|---|---|
| 1. Theo IP | Một máy gửi dồn dập request | IP client (IPv6 gộp theo /64) | `RequestRateLimitFilter`, ngay sau CORS trong security chain. Chạy trước khi đọc body, validate, mở transaction hay truy cập DB |
| 2. Theo email | Đoán mật khẩu một tài khoản, kể cả khi đổi IP | email đã chuẩn hoá (trim, chữ thường) | `AuthenticationService.login` qua port `LoginAttemptGuard` |

## Giới hạn mặc định

| Policy | Giới hạn | Đếm gì |
|---|---|---|
| `auth.login` | 20 / 1 phút / IP | Mọi request login, kể cả 400/401 |
| `auth.login-failures` | 5 / 15 phút / email | Chỉ lần sai email/mật khẩu (`INVALID_CREDENTIALS`) |
| `auth.register` | 10 / 1 giờ / IP | Mọi request đăng ký, kể cả 400/409, nên cũng làm chậm việc dò `EMAIL_EXISTS` / `RESTAURANT_CODE_EXISTS` |
| `auth.refresh` | 60 / 1 phút / IP | Mọi request refresh |

Giới hạn theo IP để rộng vì cả nhà hàng thường dùng chung một IP wifi lúc nhân viên vào ca. Lớp chính chống đoán mật khẩu là lớp theo email.

## Response khi bị chặn

| HTTP | `code` | Khi nào |
|---|---|---|
| 429 | `TOO_MANY_REQUESTS` | Lớp 1: IP vượt giới hạn của endpoint |
| 429 | `LOGIN_TEMPORARILY_LOCKED` | Lớp 2: email đã sai đủ số lần trong cửa sổ. Kể cả lần này nhập **đúng** mật khẩu vẫn bị chặn tới hết cửa sổ |

- Luôn có header `Retry-After: <số giây>` và `Cache-Control: no-store`. CORS đã expose `Retry-After`.
- Body cùng format lỗi chung: `{"success": false, "code": "...", "message": "...", "fieldErrors": {}, "timestamp": "..."}`.
- Đăng nhập thành công xoá bộ đếm sai của email đó.
- Email không tồn tại bị đếm và khoá **y hệt** email có thật, nên việc bị khoá không tiết lộ email nào đã đăng ký.
- Login luôn chạy đúng một lần BCrypt (`CredentialVerifier` so với một hash giả khi không có tài khoản), nên thời gian phản hồi cũng không tiết lộ email hay tài khoản bị vô hiệu hoá.
- Lần bị khoá chỉ ghi log `WARN` (không có email). Không ghi `audit_logs` cho request bị chặn, để kẻ tấn công không gây tải DB được.

## Frontend cần làm

- Gặp `429`: đọc `Retry-After`, hiển thị "Thử lại sau X phút" (làm tròn lên), tạm khoá nút gửi.
- `LOGIN_TEMPORARILY_LOCKED`: "Bạn đã nhập sai quá nhiều lần. Vui lòng thử lại sau X phút."
- `TOO_MANY_REQUESTS` khi refresh: không xoá phiên ngay; chờ `Retry-After` rồi thử lại. Chỉ đăng xuất khi nhận `401 INVALID_REFRESH_TOKEN`.

## Cấu hình

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `RATE_LIMIT_ENABLED` | `true` | `false` thay bằng `NoopRateLimiter` (cho qua hết). Test đặt `false` trong `src/test/resources/application.properties` |
| `RATE_LIMIT_MAX_ENTRIES_PER_POLICY` | `20000` | Số bộ đếm tối đa giữ trong RAM cho mỗi policy |
| `AUTH_LOGIN_RATE_LIMIT` / `AUTH_LOGIN_RATE_WINDOW` | `20` / `1m` | |
| `AUTH_LOGIN_FAILURE_LIMIT` / `AUTH_LOGIN_FAILURE_WINDOW` | `5` / `15m` | |
| `AUTH_REGISTER_RATE_LIMIT` / `AUTH_REGISTER_RATE_WINDOW` | `10` / `1h` | Lúc demo cần tạo nhiều nhà hàng từ một máy thì tăng giá trị này |
| `AUTH_REFRESH_RATE_LIMIT` / `AUTH_REFRESH_RATE_WINDOW` | `60` / `1m` | |

`deploy/docker-compose.yml` truyền sẵn các biến `*_LIMIT` và `RATE_LIMIT_ENABLED`. Giá trị sai (limit < 1, window ≤ 0) làm app không khởi động được.

## Giới hạn của cách làm hiện tại

- Bộ đếm nằm trong RAM của một JVM (`InMemoryFixedWindowRateLimiter`): đúng khi chạy **một** container backend như hiện tại; restart sẽ xoá bộ đếm. Khi chạy nhiều instance, thêm một implementation `RateLimiter` dùng Redis, không cần sửa nơi gọi.
- Cửa sổ cố định: ở ranh giới hai cửa sổ có thể lọt tối đa gấp đôi giới hạn trong thời gian ngắn.
- Bộ đếm của một policy đầy: key mới bị từ chối (fail-closed, không đẩy bộ đếm đang sống ra). Riêng bộ đếm sai mật khẩu khi đầy thì bỏ qua lần ghi mới; lớp theo IP vẫn còn hiệu lực.
- Lớp 1 dựa vào `getRemoteAddr()`. Trên prod, giá trị này là IP thật của khách nhờ `server.forward-headers-strategy: native` và `trusted_proxies` của Caddy. Sau khi deploy, kiểm tra cột `audit_logs.ip_address` của các bản ghi `USER_LOGIN_FAILED` mới: nếu toàn là IP Docker `172.x` thì mọi người đang dùng chung một bộ đếm.

## Mở rộng

- Giới hạn thêm endpoint (vd. quên mật khẩu): thêm một `RequestRateLimitRule` vào `AuthRateLimitRules`, hoặc tạo bean `RequestRateLimitRules` mới trong module khác. Không cần sửa filter.

Code: `common/ratelimit/*` (abstraction), `infrastructure/ratelimit/*` (in-memory, noop), `infrastructure/security/RequestRateLimitFilter`, `modules/auth/controller/AuthRateLimitRules`, `modules/auth/application/port/LoginAttemptGuard`, `modules/auth/service/RateLimitedLoginAttemptGuard`, `modules/auth/service/CredentialVerifier`, `config/RateLimitProperties`, `config/AuthRateLimitProperties`.
