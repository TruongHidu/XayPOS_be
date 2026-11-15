# Truyền refresh token: JSON body (mobile) và cookie HttpOnly (web)

`/api/v1/auth/login`, `/register-restaurant`, `/refresh`, `/logout` hỗ trợ hai cách truyền refresh token, chọn theo header của từng request.

| | Mobile / client khác (mặc định) | Trình duyệt (`X-Auth-Transport: cookie`) |
|---|---|---|
| Login / register / refresh trả về | `refreshToken` trong JSON | `refreshToken: null`; token ở `Set-Cookie: xaypos_rt=…; HttpOnly; SameSite=Strict; Path=/api/v1/auth; Max-Age=<refresh TTL>` (+ `Secure` ngoài dev) |
| Refresh gửi lên | `{"refreshToken": "…"}` | Không body; cookie tự gửi kèm (`withCredentials`) |
| Logout | `{"refreshToken": "…"}` | Không body; response xoá cookie (`Max-Age=0`) |

- Mobile không cần thay đổi gì.
- Refresh token bị từ chối (hết hạn, bị thu hồi, dùng lại) ở chế độ cookie: response 401 `INVALID_REFRESH_TOKEN` kèm xoá cookie.
- Logout là `permitAll`: có refresh token (cookie hoặc body) là đủ, nên vẫn đăng xuất được khi access token đã hết hạn. Không có token và không đăng nhập thì 401 `UNAUTHORIZED`.
- Chống CSRF (CSRF đang tắt vì API stateless): cookie `SameSite=Strict` và chỉ gửi tới `/api/v1/auth`; header tự định nghĩa `X-Auth-Transport` buộc trình duyệt gửi preflight CORS, nên site lạ không gửi được.
- Rotation vẫn như cũ: dùng lại refresh token đã xoay vòng sẽ thu hồi mọi phiên của user. Web dùng Web Locks để các tab refresh lần lượt.

## Cấu hình

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `AUTH_COOKIE_SECURE` | `true` (profile `dev`: `false`) | Bắt buộc `true` khi chạy HTTPS |
| `JWT_ACCESS_TTL` | `15m` | Web và mobile tự gia hạn bằng refresh token |
| `JWT_REFRESH_TTL` | `30d` | Thời gian giữ đăng nhập |

Code: `auth/controller/AuthTokenTransport` (chọn cách truyền), `auth/controller/RefreshTokenCookieStore` (đọc/ghi cookie), `config/RefreshCookieProperties`.
