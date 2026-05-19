# Hướng dẫn Phát triển và Triển khai Microservice

Tài liệu này cung cấp hướng dẫn chi tiết về cấu trúc thư mục chuẩn, quy trình phát triển local, đóng gói image và kiểm thử tích hợp cho các microservice trong dự án.

---

## 1. Cấu trúc Thư mục Chuẩn (Standard Directory Structure)

Tất cả các service cần tuân thủ cấu trúc package đồng nhất như sau:

```text
service/
├── src/
│   ├── main/
│   │   ├── java/com/notfound/__serviceName__/
│   │   │   ├── client/        # OpenFeign Clients (giao tiếp API ngoại vi)
│   │   │   ├── config/        # Cấu hình hệ thống (Security, Redis, Swagger, ...)
│   │   │   ├── controller/    # Định nghĩa các REST Endpoints
│   │   │   ├── exception/     # Xử lý lỗi toàn cục (Global Exception Handling)
│   │   │   ├── listener/      # Xử lý các sự kiện (Events, Message Listeners)
│   │   │   ├── memory/        # Quản lý bộ nhớ đệm hoặc dữ liệu tạm thời
│   │   │   ├── model/         # Định nghĩa các đối tượng dữ liệu
│   │   │   │   ├── converter/     # Chuyển đổi dữ liệu (JPA Converters)
│   │   │   │   ├── dto/           # Data Transfer Objects (Request/Response)
│   │   │   │   ├── entity/        # Ánh xạ cơ sở dữ liệu (JPA Entities)
│   │   │   │   ├── enums/         # Các hằng số định nghĩa (Enumerations)
│   │   │   │   └── mapper/        # Công cụ ánh xạ Entity <-> DTO
│   │   │   ├── repository/    # Tầng giao tiếp cơ sở dữ liệu (Spring Data JPA)
│   │   │   ├── security/      # Cấu hình bảo mật và phân quyền
│   │   │   ├── service/       # Tầng xử lý logic nghiệp vụ (Business Logic)
│   │   │   │   └── impl/          # Triển khai chi tiết logic
│   │   │   ├── util/          # Các lớp tiện ích bổ trợ
│   │   │   └── messaging/     # Tích hợp Message Queue (RabbitMQ, Kafka, ...)
│   │   │       ├── producer/      # Các thành phần gửi tin nhắn
│   │   │       └── consumer/      # Các thành phần nhận tin nhắn
│   │   └── resources/         # Tài nguyên hệ thống và cấu hình
│   │        ├── application-dev.yml   # Môi trường phát triển (Development)
│   │        ├── application-prod.yml  # Môi trường vận hành (Production)
│   │        └── application.yml       # Cấu hình mặc định chung
│   └── test/                  # Unit test và Integration test
├── target/                   # Thư mục chứa kết quả build (Tự động tạo)
├── pom.xml                   # Cấu hình Maven project
└── ...
```

---

## 2. Quy trình Phát triển tại Local (Local Development)

Để bắt đầu phát triển service tại máy cá nhân, vui lòng thực hiện theo các bước sau:

1.  **Khởi động Docker**: Đảm bảo Docker Desktop đã được kích hoạt.
2.  **Tru cập thư mục service**: Sử dụng terminal di chuyển vào thư mục của service tương ứng.
3.  **Chạy hạ tầng**: Thực hiện lệnh khởi động container:
    ```bash
    docker compose up -d
    ```
4.  **Tự động cập nhật code (Hot Reload)**: Sau khi chỉnh sửa code, hãy chạy lệnh sau để container tự động biên dịch và áp dụng thay đổi mà không cần restart:
    ```bash
    ./mvnw compile
    ```

---

## 3. Đóng gói và Đẩy Image (Build & Push)

Khi tính năng đã sẵn sàng để triển khai, thực hiện quy trình build image:

1.  **Dọn dẹp và đóng gói**:
    ```bash
    ./mvnw clean package
    ```
2.  **Đẩy image lên Docker Registry**: Sử dụng script hỗ trợ để push image với tag phiên bản cụ thể:
    ```powershell
    ./push.ps1 v1.0.0
    ```

---

## 4. Kiểm thử Tích hợp Hệ thống (Integration Testing)

Để kiểm tra sự phối hợp giữa service mới build với toàn bộ hệ thống:

1.  **Khởi động hạ tầng chung (Infra)**:
    ```bash
    docker compose -f docker-compose.dev.yml up -d
    ```
2.  **Build và chạy service tích hợp**: Tại thư mục gốc của dự án, thực hiện:
    ```bash
    ./mvnw clean package
    docker compose up --build -d
    ```
3.  **Kiểm tra trạng thái**: Đảm bảo tất cả các service hiển thị trạng thái `Running` ổn định.

> [!IMPORTANT]
> **Lưu ý về Cấu hình Port:** Tuyệt đối **KHÔNG** tự ý thay đổi các giá trị port trong các file `docker-compose.dev.yml`, `docker-compose.prod.yml`, và `docker-compose.yml` để tránh xung đột hệ thống và lỗi kết nối giữa các microservice.

---

## Phụ lục: Mẫu Cấu hình Database (MySQL)

Dưới đây là mẫu định nghĩa database cho một service mới (ví dụ: `book-db`):

```yaml
book-db:
  image: mysql:8.0
  container_name: book-db
  restart: always
  environment:
    MYSQL_DATABASE: bookstore_book
    MYSQL_USER: bookstore
    MYSQL_PASSWORD: bookstore
    MYSQL_ROOT_PASSWORD: root
  healthcheck:
    test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-u", "root", "-proot"]
    interval: 5s
    timeout: 5s
    retries: 20
```
