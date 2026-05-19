# JWT public key

Gateway chi can **`public.pem`** de verify JWT RS256. Gateway khong can va khong nen mount `private.pem`.

## Dev/local

Khi chay local/dev:

```text
APP_JWT_KEYS_DIR=./key
```

Thu muc nay can co:

```text
key/public.pem
```

File `public.pem` phai cung cap voi private key ma `bookstore-user-service` dung de sign token.

## Docker Compose dev

`docker-compose.yml` mount:

```text
./key:/key:ro
APP_JWT_KEYS_DIR=/key
```

## Kubernetes production

Production khong doc file tu repo. Jenkins tao Kubernetes Secret tu Jenkins Credentials:

```text
Credential ID: jwt-public-pem
Credential type: Secret file
Secret file content: public.pem
```

Jenkinsfile tao secret:

```text
api-gateway-jwt-public-key
```

Deployment mount secret do vao:

```text
/key/public.pem
```

Production env:

```text
APP_JWT_KEYS_DIR=/key
```
