# FE sync — kiểm kê kho

Không có thay đổi Flutter/web trong đợt backend này. Contract đầy đủ và Postman:
[stock-audit-api.md](stock-audit-api.md), OpenAPI tenant-api.yaml.

1. Menu kiểm kê chỉ hiển thị khi có INVENTORY_VIEW và cả INVENTORY_MANAGEMENT,
   STOCK_AUDIT. Quyền quản lý STOCK_AUDIT_MANAGE và quyền chốt STOCK_AUDIT_CONFIRM
   độc lập; không dùng role name hoặc INVENTORY_ADJUST thay cho chúng.
2. Tạo DRAFT chọn kho active. Idempotency-Key UUID sinh cho một lần submit, giữ
   nguyên khi retry cùng payload; không đổi key chỉ vì timeout. DRAFT chưa có sheet.
3. Start gửi expectedVersion; khi thành công load header và items bằng paging.
   Không giới hạn sheet ở 100 dòng đầu. Backend không khóa kho trong lúc đếm.
4. Ô chưa đếm hiển thị trống, không tự gán 0. Số đếm là vật lý gồm hàng reserved.
   Hiển thị unit snapshot và hệ thống/chênh lệch, không tự quy đổi kg/g.
5. PATCH items tối đa 100 dòng, dùng auditItemId, không ingredientId. Gửi tuần tự
   từng batch, cập nhật version từ response rồi gửi batch tiếp. Không gửi song song
   các batch chung version, không cập nhật toàn bộ sheet khi chỉ vài dòng đổi.
6. Version thuộc header audit. 409 CONCURRENT_STOCK_AUDIT_UPDATE: reload header +
   trang liên quan, hỏi nhân viên đối chiếu trước khi gửi lại; không ghi đè âm thầm.
   No-op không tăng version. Request lỗi rollback cả batch.
7. Confirm bật khi đủ count và có quyền. Backend vẫn quyết định cuối cùng.
   STOCK_AUDIT_SNAPSHOT_STALE không phải thành công: giữ dữ liệu nhập để tham khảo,
   hướng dẫn cancel/tạo/start mới và đếm lại. Không tái sử dụng số đếm cũ tự động.
8. INSUFFICIENT_AVAILABLE_STOCK: tồn đếm nhỏ hơn giữ chỗ hiện tại; không tự giảm
   reserved/hủy order. Gợi ý đối chiếu thực tế và quy trình nghiệp vụ, không sửa giá trị giả.
9. CONFIRMED hiển thị link adjustmentDocumentId nếu có; null nghĩa là không chênh
   lệch, không phải lỗi. Show reference cost, không gắn nhãn actual COGS/lợi nhuận.
10. Stock document có stockAuditId != null chỉ đọc. Ẩn mọi nút sửa/confirm/cancel
    document đó; backend cũng chặn. Không gửi stockAuditId/origin hoặc key SYS_STOCK_AUDIT:.
11. Start/confirm/cancel replay trả trạng thái hiện tại, không tạo lần kiểm kê/chứng
    từ mới. Sau timeout confirm, GET/retry chính audit, không tạo manual ADJUSTMENT.
12. Cảnh báo UI: kiểm kê lúc ít giao dịch/cuối ca; nhập/xuất/chế biến có thể làm
    phải đếm lại. Tên/SKU/unit trong sheet là lịch sử, không thay bằng catalog live.
