# JWT public key (`/key`)

Gateway chỉ cần **`public.pem`** (cùng file với `bookstore-user-service/key/public.pem`).

Xem `bookstore-user-service/key/README.md` để tạo hoặc đồng bộ key.

- Local: `APP_JWT_KEYS_DIR=./key`
- Docker/K8s: mount thư mục tại `/key`
