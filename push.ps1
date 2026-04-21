param([string]$tag)

if (-not $tag) {
    Write-Host "Nhập tag"
    exit
}

docker build -t truongikpk/bookstore-api-gateway:$tag .
docker push truongikpk/bookstore-api-gateway:$tag

# .\push.ps1 v1
# ./mvnw compile