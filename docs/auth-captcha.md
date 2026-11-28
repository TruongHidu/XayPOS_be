# CAPTCHA cho đăng ký nhà hàng (Cloudflare Turnstile)

`POST /api/v1/auth/register-restaurant` chỉ chạy khi trình duyệt gửi kèm một token Turnstile hợp lệ trong header `X-Captcha-Token`. Token chứng minh người dùng thật đã qua thử thách của Cloudflare. Với widget chế độ **Managed**, phần lớn người dùng không phải bấm gì.

## Luồng

```
RegisterPage ── widget Turnstile ──► token (hết hạn sau 5 phút, chỉ dùng 1 lần)
POST /register-restaurant + X-Captcha-Token
  → CORS → RequestRateLimitFilter (429 nếu vượt 10 lần/giờ/IP)
  → CaptchaVerificationFilter ── POST siteverify (secret, response, remoteip) ──► Cloudflare
  → AuthController → RestaurantRegistrationService (không đổi)
```

- Filter CAPTCHA chạy **sau** rate limit: một đợt flood bị chặn trước khi tốn lượt gọi Cloudflare.
- Filter chạy **trước** khi đọc body: request không có CAPTCHA hợp lệ không được validate và không chạm DB.
- Token đi qua header nên DTO `RegisterRestaurantRequest` giữ nguyên.
- Fail-closed: không gọi được Cloudflare thì từ chối, không bao giờ tạo tài khoản chưa xác minh.

## Response khi bị chặn

| HTTP | `code` | Khi nào |
|---|---|---|
| 400 | `CAPTCHA_REQUIRED` | Không có header `X-Captcha-Token` |
| 400 | `CAPTCHA_INVALID` | Cloudflare trả `invalid-input-response`, `timeout-or-duplicate` (hết hạn hoặc đã dùng) hay `bad-request`; token dài quá 2048 ký tự; token cấp cho hostname khác `TURNSTILE_EXPECTED_HOSTNAME`, hoặc cho action khác `register` |
| 503 | `CAPTCHA_UNAVAILABLE` | Timeout hoặc lỗi mạng tới Cloudflare, Cloudflare trả 5xx hay `internal-error`, hoặc secret sai (`invalid-input-secret` / `missing-input-secret`, ghi log `ERROR`) |

Body cùng format lỗi chung, kèm `Cache-Control: no-store`. Log chỉ ghi error code của Cloudflare, không bao giờ ghi token.

## Frontend

- Site key lấy từ `VITE_TURNSTILE_SITE_KEY`. Để trống thì không có widget, không gửi header (dùng khi dev với backend tắt CAPTCHA).
- Widget render với `action: 'register'`. Nút "Tạo tài khoản" bị khoá tới khi có token.
- Token chỉ dùng được một lần: sau mọi lần gửi thất bại, web reset widget để lấy token mới. Lần tự thử lại khi gặp `RESTAURANT_CODE_EXISTS` cũng chờ token mới.
- Code: `src/shared/captcha/` (`turnstileScript.ts`, `TurnstileWidget.tsx`, `useCaptcha.ts`), `features/auth/pages/RegisterPage.tsx`, `features/auth/api/authApi.ts`.
- Hiện không có CSP. Nếu sau này thêm CSP, phải cho phép `https://challenges.cloudflare.com` ở `script-src` và `frame-src`.

## Cấu hình

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `CAPTCHA_ENABLED` | `false` (profile `prod`: `true`) | `false` dùng `DisabledCaptchaVerifier` (cho qua hết) |
| `TURNSTILE_SECRET_KEY` | trống | Bắt buộc khi bật; thiếu thì backend không khởi động |
| `TURNSTILE_EXPECTED_HOSTNAME` | trống (compose: `DOMAIN`) | Để trống khi dùng key test (key test luôn trả `example.com`) |
| `TURNSTILE_VERIFY_URL` | URL siteverify của Cloudflare | Chỉ đổi khi test |
| `TURNSTILE_SITE_KEY` (deploy) → `VITE_TURNSTILE_SITE_KEY` (web) | trống | Công khai, được build vào bundle |

Timeout gọi Cloudflare: kết nối 3 giây, đọc 5 giây (`app.captcha.turnstile.connect-timeout` / `read-timeout`).

## Lấy key thật (miễn phí)

1. Cloudflare Dashboard → **Turnstile** → **Add widget**.
2. Hostname: domain prod (`DOMAIN`). Muốn thử key thật trên máy thì thêm `localhost`.
3. Widget mode: **Managed**.
4. Ghi **Site key** vào `TURNSTILE_SITE_KEY`, **Secret key** vào `TURNSTILE_SECRET_KEY` trong `deploy/.env`. Không cần chuyển DNS sang Cloudflare.

## Test ở máy bằng key test của Cloudflare

| Loại | Giá trị | Kết quả |
|---|---|---|
| Site key (web) | `1x00000000000000000000AA` | Luôn qua, hiện widget, chạy được trên localhost |
| Secret (backend) | `1x0000000000000000000000000000000AA` | Luôn qua |
| Secret (backend) | `2x0000000000000000000000000000000AA` | Luôn fail → `CAPTCHA_INVALID` |
| Secret (backend) | `3x0000000000000000000000000000000AA` | "Token đã dùng" → `CAPTCHA_INVALID` |

Site key test sinh ra token giả `XXXX.DUMMY.TOKEN.XXXX`; secret thật luôn từ chối token này. Khi chạy thử, secret test `1x…AA` cho qua cả token bất kỳ, nên chỉ dùng nó để thử luồng ở máy, không dùng để kiểm tra bảo mật.

## Mở rộng

- Bắt CAPTCHA cho endpoint khác: thêm một `CaptchaProtectedRequest` vào `AuthCaptchaRequests`, hoặc tạo bean `CaptchaProtectedRequests` trong module khác. Không cần sửa filter.
- Đổi nhà cung cấp (hCaptcha, reCAPTCHA): thêm một implementation `CaptchaVerifier`.

Code backend: `common/captcha/*`, `config/CaptchaProperties`, `infrastructure/captcha/TurnstileCaptchaVerifier`, `infrastructure/captcha/DisabledCaptchaVerifier`, `infrastructure/security/CaptchaVerificationFilter`, `modules/auth/controller/AuthCaptchaRequests`.
