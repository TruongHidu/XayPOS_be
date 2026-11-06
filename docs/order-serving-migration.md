# V19: một serving order mỗi phiên

V19 chỉ thêm index, không thêm bảng và không thay V1–V18. Predicate:
DINE_IN, session khác null, status OPEN/CONFIRMED/PREPARING/READY/SERVED.
Payment status không tham gia predicate. CANCELLED/COMPLETED vẫn giữ lịch sử.

Migration preflight fail nếu cùng tenant/session có nhiều serving orders. Không
delete/merge/cancel/chọn order thắng tự động; sau khi lấy quyết định nghiệp vụ,
operator xử lý dữ liệu theo quy trình riêng rồi chạy lại migration.

## Chẩn đoán read-only

Chạy trong đúng schema ứng dụng đã xác minh (SQL dưới đây dùng search_path hiện tại):

    SELECT current_database(), current_schema();

    SELECT restaurant_id, table_session_id, count(*) AS serving_count,
           array_agg(id ORDER BY created_at, id) AS order_ids
    FROM orders
    WHERE service_type = 'DINE_IN'
      AND table_session_id IS NOT NULL
      AND status IN ('OPEN','CONFIRMED','PREPARING','READY','SERVED')
    GROUP BY restaurant_id, table_session_id
    HAVING count(*) > 1
    ORDER BY restaurant_id, table_session_id;

Chỉ identifiers/counts; không dump customer/phone/note. Không dùng findFirst/latest
để che dữ liệu lỗi. Không chạy Flyway clean trên dev/public. V19 có preflight và
CREATE UNIQUE INDEX cùng migration transaction; lỗi không để lại index nửa chừng.

## Sau upgrade

Partial unique ux_order_serving_session bảo vệ cả direct SQL lẫn application race.
ORDER precheck dưới restaurant lock và constraint failure đều map HTTP 409
TABLE_SESSION_HAS_SERVING_ORDER. Không query/retry trong transaction đã lỗi.
Không cần thêm idempotency ledger: create namespace/append ledger hiện có giữ nguyên.

Predicate Java ở OrderSessionServingPolicy.SERVING_STATUSES phải khớp SQL.
Thêm status mới sau này cần migration thay index và cập nhật policy/tests.
Financial session usage/cancellation guard là rule khác, không thay bằng serving count.
