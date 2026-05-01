package com.hamtech.bookstoreapigateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.PublicKey;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.function.Predicate;

/**
 * Gateway Authentication Filter sử dụng RS256.
 *
 * Flow theo plan.md:
 *   1. Client gửi request kèm JWT trong header Authorization
 *   2. Gateway verify JWT bằng PUBLIC KEY (không cần gọi User Service)
 *   3. Decode claims → inject X-User-Id, X-User-Role vào headers
 *   4. Forward request tới downstream service
 *
 * Lợi ích RS256 so với HS256:
 *   - Gateway chỉ cần public key (không cần shared secret)
 *   - Không cần HTTP call tới User Service → giảm latency
 *   - User Service giữ private key an toàn
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    private final PublicKey publicKey;

    public AuthenticationFilter() {
        this.publicKey = loadPublicKey();
    }

    /**
     * Load public key theo thứ tự ưu tiên:
     *
     * 1) ENV `JWT_PUBLIC_CERT_PEM`: X.509 certificate PEM (-----BEGIN CERTIFICATE----- ...).
     *    - Khuyến nghị: inject bằng .env / Docker secret thay vì commit vào resources.
     * 2) ENV/Property `APP_JWT_PUBLIC_KEY`: Base64 X509-encoded RSA public key.
     * 2) Fallback: classpath `keys/public.pem` (giữ lại để không phá dev hiện tại).
     */
    private PublicKey loadPublicKey() {
        try {
            String certPem = firstNonBlank("JWT_PUBLIC_CERT_PEM");
            if (certPem != null && !certPem.isBlank()) {
                return parsePublicKeyFromCertificatePem(certPem);
            }

            String appJwtPublicKey = firstNonBlank("APP_JWT_PUBLIC_KEY");
            if (appJwtPublicKey != null && !appJwtPublicKey.isBlank()) {
                return parsePublicKeyFromX509Base64(appJwtPublicKey);
            }

            InputStream is = new ClassPathResource("keys/public.pem").getInputStream();
            try {
                return parsePublicKeyFromCertificateStream(is);
            } finally {
                is.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Không thể load public key từ keys/public.pem. " +
                    "Khuyến nghị: set ENV/Property JWT_PUBLIC_CERT_PEM (X.509 certificate PEM) " +
                    "hoặc APP_JWT_PUBLIC_KEY (Base64 X509 public key) cho api-gateway.", e);
        }
    }

    private String firstNonBlank(String key) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v;
        v = System.getProperty(key);
        if (v != null && !v.isBlank()) return v;
        return null;
    }

    private PublicKey parsePublicKeyFromCertificatePem(String pemOrBase64) throws Exception {
        // Hỗ trợ 2 dạng:
        // - PEM certificate: -----BEGIN CERTIFICATE----- ... -----END CERTIFICATE-----
        // - Base64 DER (không header/footer)
        byte[] certBytes;

        String trimmed = pemOrBase64.trim();
        if (trimmed.contains("BEGIN CERTIFICATE")) {
            String normalized = trimmed
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");
            String base64 = normalized
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s+", "");
            certBytes = Base64.getDecoder().decode(base64);
        } else {
            certBytes = Base64.getDecoder().decode(trimmed.replaceAll("\\s+", ""));
        }

        try (InputStream is = new ByteArrayInputStream(certBytes)) {
            return parsePublicKeyFromCertificateStream(is);
        }
    }

    private PublicKey parsePublicKeyFromCertificateStream(InputStream is) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
        return cert.getPublicKey();
    }

    private PublicKey parsePublicKeyFromX509Base64(String base64X509PublicKey) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64X509PublicKey.replaceAll("\\s+", ""));
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Các endpoint công khai không cần xác thực
        final List<String> publicEndpoints = List.of(
                "/api/auth/register",
                "/api/auth/login",
                "/api/auth/send-otp",
                "/api/auth/verify-otp",
                "/api/auth/confirm-email",
                "/api/auth/refresh-token",
                "/api/v1/auth/register",
                "/api/v1/auth/login"
        );

        Predicate<ServerHttpRequest> isApiSecured = r -> publicEndpoints.stream()
                .noneMatch(uri -> r.getURI().getPath().contains(uri));

        if (isApiSecured.test(request)) {
            // 1. Kiểm tra Authorization header
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                return onError(exchange, "Missing Authorization Header", HttpStatus.UNAUTHORIZED);
            }

            String authHeader = request.getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION).get(0);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return onError(exchange, "Invalid Authorization Header", HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(7);

            try {
                // 2. Verify JWT bằng PUBLIC KEY (RS256) — stateless, không cần gọi User Service
                Claims claims = Jwts.parser()
                        .verifyWith(publicKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                String userId = claims.get("userId", String.class);

                // 3. Inject user info vào headers cho downstream services
                ServerHttpRequest mutatedRequest = request.mutate()
                        .header("X-User-Name", username != null ? username : "")
                        .header("X-User-Role", role != null ? role : "")
                        .header("X-User-Id", userId != null ? userId : "")
                        .build();

                return chain.filter(exchange.mutate().request(mutatedRequest).build());

            } catch (Exception e) {
                return onError(exchange, "Token invalid or expired: " + e.getMessage(), HttpStatus.UNAUTHORIZED);
            }
        }

        return chain.filter(exchange);
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\": " + httpStatus.value() + ", \"message\": \"" + err + "\"}";
        byte[] bytes = body.getBytes();
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        return -1; // Execute before default routing mechanism
    }
}
