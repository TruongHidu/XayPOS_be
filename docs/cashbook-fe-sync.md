# Đồng bộ FE — CASHBOOK

Tích hợp 3 nhóm màn hình: sổ thu chi (entries + summary/series), quản lý phiếu chi,
và đối chiếu phiếu nhập. [API đầy đủ/Postman](cashbook-api.md) · [OpenAPI](openapi/cashbook-api.yaml).

- Đăng nhập lại/refresh session sau seed permission nếu token chưa có quyền CASHBOOK.
  Đọc feature effective, không hard-code package/role. Thiếu CASHBOOK_VIEW ẩn phần đọc;
  CASHBOOK_CREATE cho tạo chi; CASHBOOK_VOID cho hủy ghi nhầm. Backend quyết định cuối.
- Bộ lọc khoảng ngày là ngày địa phương restaurant, không tự chuyển ngày bằng timezone
  thiết bị. Truyền currency khi xem lịch sử; một response một currency.
- Gọi summary/series với đúng filter của entries, loại page/sort; không tự cộng trang
  đang hiển thị thành tổng kỳ. Hiển thị “Chênh lệch thu chi”, không “Lợi nhuận/Số dư quỹ”.
- Phân biệt ngày thực chi expenseAt và ngày ghi sổ createdAt; user chọn đã trả tiền rồi
  mới tạo phiếu POSTED. Không có DRAFT. Không tạo thu bán hàng thủ công cho payment SUCCESS.
- Form chi dùng category API, CASH/TRANSFER, số tiền dương tối đa 2 chữ số lẻ. Gửi
  expenseAt có timezone/UTC, currency, reference/payee/note theo nhu cầu.
- Mỗi lần bấm ghi nhận tạo Idempotency-Key; retry mạng giữ key và payload. Người dùng
  đổi nội dung để tạo khoản khác phải dùng key mới. Disable double tap chỉ hỗ trợ UX;
  backend chống trùng. Replay có thể trả VOIDED; không hiển thị như chi mới.
- Nút chọn phiếu nhập gọi cashbook/stock-receipts với quyền/feature inventory; không dùng
  totalCost làm khoản chi tự động. Có thể gợi ý amount, nhưng user xác nhận thực trả.
  Nhiều lần chi cùng phiếu không đồng nghĩa công nợ/đã tất toán.
- Phiếu nguồn UNKNOWN currency: không gắn ký hiệu currency hiện tại vào totalCost cũ;
  không tính còn nợ. Amount của phiếu chi mới vẫn có currency riêng được xác nhận.
- Ghi sai: gọi void(reason, expectedVersion), giữ lịch sử; không gửi PATCH/DELETE và
  không gọi payment refund. Sau tạo/void tải lại entries, summary, series, phiếu chi và
  đối chiếu receipt liên quan. Void làm báo cáo kỳ cũ thay đổi theo dữ liệu đã sửa.
- Danh sách phiếu chi có thể lọc VOIDED để xem lịch sử. Dòng tiền hiệu lực chỉ POSTED.
- Key của cashbook row phải là sourceType + sourceId; UUID đơn lẻ không đủ giữa hai nguồn.
- 409 version: tải lại detail, giữ nội dung nhập và yêu cầu người dùng xem trạng thái mới.
  403 tải lại quyền/entitlement; không retry vô hạn. Các lỗi validation hiển thị theo code.
- GET không cung cấp dữ liệu riêng của khách hoặc provider. Điều hướng sang order/payment
  detail vẫn phải theo guard riêng của các API đó; CASHBOOK_VIEW không thay quyền của chúng.
