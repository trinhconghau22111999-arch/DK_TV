# QR Cam (QrtuxaCam) — biến điện thoại cũ thành webcam cho máy tính

Ứng dụng Android biến 1 điện thoại thành **webcam không dây**, truyền hình ảnh
camera SAU theo thời gian thực sang ứng dụng **Qrtuxa** trên máy tính qua
WebRTC. Đây **không phải** ứng dụng camera-giám-sát-2-chiều-giữa-2-điện-thoại —
phía "xem" luôn là app máy tính Qrtuxa, không phải một điện thoại khác.

> Repo liên quan: [Qrtuxa](https://github.com/trinhconghau22111999-arch/Qrtuxa)
> (app máy tính, đóng vai trò "máy xem" + ghi hình + các tính năng khác).

## App làm gì (đúng như code hiện tại)

- App chỉ có **1 vai trò duy nhất**: máy camera. Mở app là vào thẳng màn hình
  camera, không có bước chọn vai trò.
- Màn hình đầu tiên bắt tick đồng ý ("Tôi đồng ý dùng điện thoại này làm
  webcam") mới bật được nút "Bắt đầu làm Webcam".
- Bấm nút sẽ xin quyền **Camera** (và **Thông báo** trên Android 13+) bằng hộp
  thoại hệ thống.
- Sau khi cấp quyền, app sinh ra **1 mã 6 số cố định vĩnh viễn cho máy này**
  (chỉ sinh ngẫu nhiên đúng 1 lần, lưu lại và dùng mãi mãi kể cả sau khi dừng
  webcam hay khởi động lại máy) — đọc mã này để nhập vào ô "QR Cam" trên
  Qrtuxa.
- App tự chọn ống kính SAU có **góc nhìn rộng nhất** (so sánh FOV tính từ
  thông số cảm biến + tiêu cự từng ống, không phải ống mặc định), quay
  **1280x720 @ 20fps**, và bắt đầu chạy `CameraStreamService` (foreground
  service, loại `camera`).
- Phục vụ được tối đa **4 máy xem cùng lúc** (4 phiên Qrtuxa khác nhau nhập
  cùng mã), mỗi máy xem có kênh WebRTC + cơ chế tự kết nối lại (backoff) hoàn
  toàn độc lập — 1 máy rớt mạng không ảnh hưởng các máy khác.
- **Một chiều duy nhất**: máy tính không có bất kỳ cách nào gửi lệnh điều
  khiển hay thao tác ngược lại điện thoại.
- Thông báo (notification) luôn hiển thị khi đang chạy, có nút "Kết thúc"
  ngay trên thông báo, và hiện số máy đang xem dạng `x/4`.
- Bấm **nút Back sẽ đưa app xuống nền** (`moveTaskToBack`) chứ không đóng hẳn
  — webcam vẫn tiếp tục chạy, vì đây vốn là app chạy nền theo thiết kế.
- Có banner + nút nhắc loại trừ tối ưu hoá pin ngay trên màn hình chính nếu
  chưa được cấp, để tránh hệ thống tắt ngầm webcam khi màn hình tắt lâu.
- Tự khởi động lại webcam sau khi **khởi động lại máy** hoặc **app được cập
  nhật**, nếu phiên đang hoạt động lúc đó (`BootReceiver`).
- Bấm "Dừng Webcam" (trong app hoặc trên thông báo) sẽ tắt camera + xoá danh
  sách máy xem trên Firebase, nhưng **giữ nguyên mã cố định** để dùng lại lần
  sau — không phải đọc mã mới mỗi lần.

## Kiến trúc

- `MainActivity` → mở thẳng `CameraActivity` (không có màn chọn vai trò).
- `CameraActivity`: màn hình đồng ý + xin quyền + hiển thị mã + nút dừng.
- `CameraStreamService`: foreground service giữ camera + WebRTC sống, quản lý
  wake lock, danh sách máy xem, và thông báo.
- `webrtc/PeerConnectionManager.kt`, `webrtc/SignalingClient.kt`: lớp WebRTC +
  trao đổi tín hiệu (offer/answer/ICE) qua Firebase.
- `BatteryOptimizationHelper.kt`: banner + nút xin miễn trừ tối ưu hoá pin.
- `BootReceiver.kt`: tự khởi động lại webcam sau khi reboot/cập nhật app.
- **Signaling (ghép nối với máy tính):** Firebase Realtime Database, đường dẫn
  `rooms/{mã 6 số}` — không cần tự dựng server riêng.
- **Truyền video:** WebRTC trực tiếp giữa điện thoại và máy tính.

## Bước 1 — Tạo dự án Firebase

1. Tạo project trên [Firebase Console](https://console.firebase.google.com),
   thêm app Android với package name khớp `applicationId` trong
   `app/build.gradle.kts` (hiện tại: `Com.qrtuxacam.name`).
2. Tải file `google-services.json` từ Firebase Console, đặt vào `app/`
   (file này đã có trong `.gitignore`, sẽ không bị commit lên repo public).
3. Firebase Console → **Realtime Database** → **Create database**.

### Realtime Database Rules

App này chỉ cần đọc/ghi dưới `rooms/{mã 6 số}`:

```json
{
  "rules": {
    "rooms": {
      "$roomCode": {
        ".read": true,
        ".write": true
      }
    }
  }
}
```

⚠️ **Nếu dùng chung 1 Firebase project với app Qrtuxa trên máy tính** (project
mặc định hiện tại tên `qrremod`), Qrtuxa còn dùng thêm đường dẫn
`sessions/{id}` cho tính năng "chia sẻ QR" riêng của nó — đừng dán đè rules chỉ
có `rooms` mà mất luôn quyền của `sessions`. Xem file rules đầy đủ (cả 2 nhánh)
trong README của repo
[Qrtuxa](https://github.com/trinhconghau22111999-arch/Qrtuxa).

## Bước 2 — Build APK bằng GitHub Actions (không cần máy tính cài Android Studio)

1. Vào repo → **Settings → Secrets and variables → Actions → New repository
   secret**, đặt tên `GOOGLE_SERVICES_JSON_BASE64`, dán vào giá trị là chuỗi
   base64 của file `google-services.json` bạn tải ở Bước 1:
   ```
   base64 -w0 google-services.json
   ```
   Dán kết quả vào ô Secret — **không dán vào bất kỳ file nào được commit lên
   repo**, kể cả README này.
2. Vào tab **Actions**, chạy workflow build APK (hoặc chỉ cần push lên nhánh
   `main`).
3. Sau khi build xong, mở run vừa chạy → mục **Artifacts** → tải APK về, cài
   vào điện thoại.

## Bước 3 — Sử dụng

1. Mở app trên điện thoại muốn dùng làm webcam.
2. Tick đồng ý → bấm "Bắt đầu làm Webcam" → cấp quyền Camera (và Thông báo
   nếu được hỏi).
3. Đọc mã 6 số hiện ra, nhập đúng mã này vào ô **QR Cam** trên app Qrtuxa ở
   máy tính.
4. Nếu thấy banner nhắc về pin, bấm nút bên cạnh để loại trừ tối ưu hoá pin
   cho app — giúp webcam không bị hệ thống tắt ngầm khi màn hình tắt lâu.
5. Muốn dừng: bấm "Dừng Webcam" trong app, hoặc bấm "Kết thúc" ngay trên
   thông báo. Mã cố định vẫn giữ nguyên cho lần dùng sau.

## Nguyên tắc thiết kế cần giữ nguyên khi chỉnh sửa

- App này **không** lưu hay ghi hình bất kỳ đoạn video nào — chỉ truyền hình
  ảnh trực tiếp một chiều. Việc ghi hình/lưu trữ (nếu có) là trách nhiệm của
  app Qrtuxa phía máy xem, không phải app này.
- Không có kênh nào để máy tính gửi lệnh điều khiển ngược lại điện thoại.
- Mã ghép nối là **cố định vĩnh viễn theo từng máy**, không đổi mỗi lần mở
  app hay mỗi lần bắt đầu phiên mới.
- Notification "đang hoạt động" luôn hiển thị trong lúc service nền đang
  chạy, không được ẩn.
- Bấm Back luôn đưa app xuống nền, không đóng hẳn ứng dụng.

## Chế độ chỉ cùng mạng (LAN only)

Mặc định bản này **chỉ kết nối khi điện thoại và máy tính cùng Wi-Fi hoặc cùng hotspot**.

- Không dùng STUN/TURN, bỏ mọi đường đi qua Internet; video và tệp đi thẳng giữa 2 máy.
- Vẫn dùng Firebase để 2 máy tìm thấy nhau, nên mạng vẫn cần có Internet (4G của máy phát hotspot cũng được).
- Khác mạng sẽ không lên hình (đúng chủ ý).
- Muốn trở lại như cũ (cả khi khác mạng): đổi `LAN_ONLY = false` (file JS/HTML) hoặc `LanOnly.ENABLED = false` (`webrtc/LanOnly.kt`).
- Lần đầu mở trên Windows, hãy bấm **Cho phép (Allow)** ở hộp thoại Tường lửa cho mạng riêng.
- Nếu Wi-Fi công cộng bật "cách ly thiết bị", hãy dùng hotspot riêng.
