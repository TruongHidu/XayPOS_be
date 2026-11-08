# Tự động test và deploy bằng GitHub Actions

Sau khi cài đặt xong, mỗi lần **push hoặc merge vào nhánh `main`**:

```
Repo XayPOS_be  ──► test (mvn verify + Postgres) ──► SSH vào VPS ──► deploy.sh backend ──► rebuild + restart backend
Repo XayPOS_web ──► lint + test + build          ──► SSH vào VPS ──► deploy.sh web     ──► rebuild + restart web (Caddy)
```

- **Pull request:** chỉ chạy test, không deploy.
- **Test lỗi:** không deploy. VPS vẫn chạy phiên bản cũ.
- **Trước khi deploy backend:** tự backup database vào `~/backups/pre-deploy_*.dump` (giữ 5 bản gần nhất), vì phiên bản mới có thể chạy migration Flyway.
- **Backend không lên được (không healthy trong 5 phút):** job báo lỗi đỏ và in 80 dòng log cuối của backend.
- **BE và FE được push cùng lúc:** VPS build lần lượt từng cái (dùng khoá `flock`) để không hết RAM.

Các file liên quan:

| File | Vai trò |
|---|---|
| `XayPOS_be/.github/workflows/ci-cd.yml` | Workflow của backend |
| `XayPOS_web/.github/workflows/ci-cd.yml` | Workflow của frontend |
| `XayPOS_be/deploy/deploy.sh` | Script chạy trên VPS: cập nhật code, backup, build, restart, chờ healthy |

## Bước 1: Kiểm tra VPS có tự `git pull` được không

GitHub Actions sẽ SSH vào VPS và chạy `git fetch`. Lệnh này **không được hỏi mật khẩu**. Trên VPS, thử:

```bash
cd ~/pos-saas/pos-saas-be && git fetch && echo BE OK
cd ~/pos-saas/pos-saas-web && git fetch && echo FE OK
```

Nếu được hỏi username/password, chạy lệnh sau rồi `git fetch` lại một lần và nhập token (Personal Access Token) để git ghi nhớ:

```bash
git config --global credential.helper store
```

Thư mục trên VPS phải đúng tên `~/pos-saas/pos-saas-be` và `~/pos-saas/pos-saas-web`. Nếu bạn đặt ở chỗ khác, tạo **Variable** `APP_DIR` ở Bước 3.

## Bước 2: Tạo SSH key riêng cho GitHub Actions

Chạy ở **cmd trên Windows** (không phải PowerShell, không phải trong VPS):

```bat
ssh-keygen -t ed25519 -C "github-actions-xaypos" -f %USERPROFILE%\.ssh\xaypos_gha -N ""
```

Lệnh này tạo 2 file: `xaypos_gha` (khoá bí mật, dán vào GitHub) và `xaypos_gha.pub` (khoá công khai, đưa lên VPS).

Đưa khoá công khai lên VPS. Bạn sẽ phải nhập mật khẩu root lần cuối:

```bat
type %USERPROFILE%\.ssh\xaypos_gha.pub | ssh root@180.93.61.223 "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

Kiểm tra. Lệnh này phải in ra `OK` mà **không hỏi mật khẩu**:

```bat
ssh -i %USERPROFILE%\.ssh\xaypos_gha root@180.93.61.223 "echo OK"
```

## Bước 3: Khai báo Secrets trên GitHub (làm cho cả 2 repo)

Mở repo **XayPOS_be** trên GitHub → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**. Tạo các secret sau:

| Name | Value |
|---|---|
| `VPS_HOST` | `180.93.61.223` |
| `VPS_USER` | `root` |
| `VPS_SSH_KEY` | Toàn bộ nội dung file khoá bí mật (xem lệnh bên dưới) |
| `VPS_PORT` | *(chỉ cần khi SSH không dùng cổng 22)* |

Lấy nội dung khoá bí mật:

```bat
type %USERPROFILE%\.ssh\xaypos_gha
```

Copy **tất cả các dòng**, kể cả `-----BEGIN OPENSSH PRIVATE KEY-----` và `-----END OPENSSH PRIVATE KEY-----`, rồi dán vào ô Value.

Sau đó **làm lại y hệt cho repo XayPOS_web**. Secrets của mỗi repo là riêng, không dùng chung được.

Nếu thư mục trên VPS không phải `~/pos-saas`, vào tab **Variables** (cùng trang) và tạo `APP_DIR`, ví dụ `/opt/pos-saas`.

Khoá này cho phép đăng nhập root vào VPS. **Không commit file `xaypos_gha` vào git và không gửi cho ai.** Nếu bị lộ, xoá dòng tương ứng trong `~/.ssh/authorized_keys` trên VPS rồi tạo khoá mới.

## Bước 4: Push workflow lên GitHub

```bash
cd "D:/Đồ án tốt nghiệp/Code/XayPOS_be"
git add .github/workflows/ci-cd.yml deploy/deploy.sh .gitattributes docs/github-actions.md
git commit -m "Add GitHub Actions CI/CD"
git push origin main
```

```bash
cd "D:/Đồ án tốt nghiệp/Code/XayPOS_web"
git add .github/workflows/ci-cd.yml
git commit -m "Add GitHub Actions CI/CD"
git push origin main
```

## Bước 5: Theo dõi lần chạy đầu tiên

Vào repo trên GitHub → tab **Actions**. Mỗi lần push sẽ có một dòng mới:

- 🟡 đang chạy, ✅ thành công, ❌ lỗi. Bấm vào để xem log từng bước.
- Job **Build and test** của backend mất khoảng 5–10 phút. Job **Deploy to VPS** mất khoảng 2–5 phút (build lại image trên VPS).
- Deploy xong, mở `https://xaypos.hiducourse.click` để kiểm tra.

Muốn deploy lại mà không cần push code mới: tab **Actions** → chọn workflow → **Run workflow**. Với backend có tuỳ chọn **Skip tests**, chỉ dùng khi cần deploy gấp.

## Quy trình làm việc hằng ngày

1. Code trên nhánh riêng (ví dụ `super-admin`), push lên rồi tạo Pull Request vào `main`. GitHub chạy test cho PR.
2. Test xanh thì merge PR. GitHub tự deploy lên VPS.
3. Không cần SSH vào VPS để `git pull` hay `docker compose build` nữa.

Vẫn có thể deploy bằng tay trên VPS khi cần:

```bash
bash ~/pos-saas/pos-saas-be/deploy/deploy.sh all
```

## Xử lý sự cố

| Lỗi trong log Actions | Cách xử lý |
|---|---|
| `Missing secrets VPS_SSH_KEY / VPS_HOST` | Chưa tạo secret ở repo đó (Bước 3) |
| `Permission denied (publickey)` | Khoá công khai chưa được thêm vào `~/.ssh/authorized_keys` trên VPS, hoặc `VPS_SSH_KEY` bị dán thiếu dòng |
| `could not read Username for 'https://github.com'` | VPS không `git fetch` được. Làm lại Bước 1. Token GitHub có thể đã hết hạn |
| `Missing .../deploy/.env` | Thiếu file `deploy/.env` trên VPS |
| Job test backend lỗi | Mở log bước **Build and test**, hoặc tải artifact `surefire-reports` ở cuối trang |
| `backend did not become healthy` | Đọc log được in ngay bên dưới. Thường là do migration lỗi hoặc thiếu biến môi trường |

**Quay về phiên bản trước** khi bản mới có lỗi: trên máy bạn chạy `git revert <commit lỗi>` rồi push lên `main`, GitHub sẽ deploy lại bản cũ. Nếu migration đã làm hỏng dữ liệu, khôi phục từ bản `~/backups/pre-deploy_*.dump` mới nhất theo mục backup/restore trong `docs/deployment.md`.
