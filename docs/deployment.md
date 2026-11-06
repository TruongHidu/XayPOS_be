# Triển khai lên VPS bằng Docker

Stack gồm 3 container, cấu hình trong [`deploy/docker-compose.yml`](../deploy/docker-compose.yml):

```
Internet ──443/80──► web (Caddy)  ── /api/*, /ws ──► backend (Spring Boot :8080) ──► postgres (:5432)
                       └── còn lại: file tĩnh React (SPA)
```

- **web**: image build từ repo `pos-saas-web` (Vite build → Caddy). Caddy tự xin và gia hạn chứng chỉ HTTPS Let's Encrypt, phục vụ SPA và reverse proxy API + WebSocket. Vì FE và BE dùng chung một domain nên không phát sinh lỗi CORS.
- **backend**: image build từ `Dockerfile` của repo này, chạy profile `prod`. Flyway tự migrate khi khởi động.
- **postgres**: dữ liệu lưu trong volume `postgres_data`. Container này **không mở port** ra ngoài.

## 1. Chuẩn bị

- VPS Ubuntu 22.04/24.04, tối thiểu 2 vCPU / 2 GB RAM (khuyên dùng 4 GB), 25 GB SSD trở lên.
- Một domain (ví dụ `pos.example.com`) có **bản ghi A trỏ về IP của VPS**.
- Tài khoản Cloudinary. Nếu bán gói qua chuyển khoản thì cần thêm tài khoản SePay.

## 2. Cài đặt VPS (chạy một lần)

```bash
# Swap 2 GB, tránh hết RAM khi build
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Docker + Compose plugin
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER   # đăng xuất rồi đăng nhập lại

# Firewall: chỉ mở SSH, HTTP, HTTPS
sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw allow 443/udp
sudo ufw enable
```

## 3. Lấy code

Hai repo phải nằm **cạnh nhau** trong cùng thư mục cha:

```bash
mkdir -p ~/pos-saas && cd ~/pos-saas
git clone <url-repo-backend> pos-saas-be
git clone <url-repo-frontend> pos-saas-web
```

Nếu đặt repo FE ở chỗ khác, khai báo `FE_DIR` trong `.env`.

## 4. Cấu hình biến môi trường

```bash
cd ~/pos-saas/pos-saas-be/deploy
cp .env.example .env
openssl rand -base64 24   # dán vào POSTGRES_PASSWORD
openssl rand -base64 48   # dán vào JWT_SECRET
nano .env
```

Các biến bắt buộc: `DOMAIN`, `POSTGRES_*`, `JWT_SECRET`. Ở lần chạy đầu, đặt `SUPER_ADMIN_BOOTSTRAP_ENABLED=true` và điền email, mật khẩu cho tài khoản super admin.

`deploy/.env` đã nằm trong `.gitignore`, **không được commit file này**.

## 5. Chạy

```bash
cd ~/pos-saas/pos-saas-be/deploy
docker compose up -d --build
docker compose ps                 # cả 3 container phải ở trạng thái Up/healthy
docker compose logs -f backend    # chờ dòng "Started PosSaasBeApplication"
```

Mở `https://<DOMAIN>` để kiểm tra. Lần đầu Caddy mất vài giây để xin chứng chỉ.

Sau khi đăng nhập được bằng super admin, đặt `SUPER_ADMIN_BOOTSTRAP_ENABLED=false` rồi chạy `docker compose up -d`.

**VPS 2 GB:** build Maven và Vite cùng lúc có thể hết RAM. Hãy build lần lượt từng image:

```bash
docker compose build backend && docker compose build web && docker compose up -d
```

## 6. SePay webhook

Trong dashboard SePay, cấu hình webhook như sau:

- URL: `https://<DOMAIN>/api/v1/webhooks/subscriptions/sepay`
- Secret: trùng với `SEPAY_WEBHOOK_SECRET` trong `.env`

Sau đó đặt `SEPAY_ENABLED=true`, `SUBSCRIPTION_PURCHASE_ACCEPT_NEW=true`, điền các biến `SEPAY_*` rồi chạy `docker compose up -d`.

## 7. Cập nhật phiên bản mới

```bash
cd ~/pos-saas/pos-saas-be && git pull
cd ~/pos-saas/pos-saas-web && git pull
cd ~/pos-saas/pos-saas-be/deploy && docker compose up -d --build
docker image prune -f
```

Migration Flyway mới sẽ tự chạy khi backend khởi động lại.

## 8. Sao lưu / khôi phục database

```bash
# Backup (có thể đặt vào crontab chạy hằng ngày)
docker compose exec -T postgres sh -c 'pg_dump -U $POSTGRES_USER -Fc $POSTGRES_DB' > backup_$(date +%F).dump

# Restore
docker compose exec -T postgres sh -c 'pg_restore -U $POSTGRES_USER -d $POSTGRES_DB --clean --if-exists' < backup_2026-01-01.dump
```

Nên copy file backup ra ngoài VPS (máy cá nhân hoặc Google Drive).

## 9. Điều chỉnh RAM

| VPS | `BACKEND_MEM_LIMIT` | `PG_MEM_LIMIT` | `PG_SHARED_BUFFERS` |
|---|---|---|---|
| 2 GB (mặc định) | `1g` | `512m` | `256MB` |
| 4 GB | `1536m` | `1g` | `768MB` |

Heap JVM được giới hạn ở 75% `BACKEND_MEM_LIMIT`. Khi chạy thử, backend dùng khoảng 380 MB, Postgres khoảng 70 MB, Caddy khoảng 12 MB.

## 10. Xử lý sự cố

| Triệu chứng | Kiểm tra |
|---|---|
| Không có HTTPS hoặc lỗi chứng chỉ | DNS đã trỏ đúng IP chưa? Port 80/443 đã mở chưa? Xem `docker compose logs web` |
| 502 Bad Gateway | Backend chưa khởi động xong hoặc bị crash: `docker compose logs backend` |
| Backend báo lỗi `JWT_SECRET` hoặc `DB_*` | Kiểm tra lại `deploy/.env` |
| Container bị kill (`OOMKilled`) | Thêm swap, build lần lượt từng image, hoặc nâng RAM |
| Realtime không kết nối được | `DOMAIN` trong `.env` phải đúng domain đang truy cập, vì nó dùng cho kiểm tra Origin của WebSocket |

## Ghi chú kỹ thuật

- `application-prod.yml` bật `server.forward-headers-strategy: native` để backend lấy IP thật của client từ `X-Forwarded-For` do Caddy gửi. IP này được dùng cho rate limit QR và audit log. Tomcat chỉ tin header này khi request đến từ dải IP nội bộ (mạng Docker). Caddy luôn ghi đè header này nên client không giả mạo được IP.
- Muốn test nhanh bằng IP mà chưa có domain: đặt `SITE_ADDRESS=:80` và `DOMAIN=<IP-VPS>`, rồi truy cập `http://<IP-VPS>`. Lưu ý chỉ dùng để thử: không có HTTPS, và WebSocket sẽ bị chặn vì Origin `http://` không khớp với `https://<DOMAIN>`.
