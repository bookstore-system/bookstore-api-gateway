package com.notfound.bookstoreapigateway.filter;

import com.notfound.bookstoreapigateway.security.RsaKeyLoader;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Gateway Authentication Filter sử dụng RS256.
 * Public key đọc từ {@code /key/public.pem} (cùng cặp key với user-service).
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    private static final Pattern NEWS_ID_PATH =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final PublicKey publicKey;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> allowedOrigins;

    public AuthenticationFilter(
            @Value("${app.jwt.keys-dir:/key}") String keysDir,
            @Value("${app.jwt.public-key-file:public.pem}") String publicKeyFile,
            @Value("${CORS_ALLOWED_ORIGIN_MAIN:https://nhasachcongdong.id.vn}") String allowedOriginMain,
            @Value("${CORS_ALLOWED_ORIGIN_WWW:https://www.nhasachcongdong.id.vn}") String allowedOriginWww) {
        Path dir = RsaKeyLoader.resolveKeysDir(keysDir);
        this.publicKey = RsaKeyLoader.loadPublicKey(dir, publicKeyFile);
        this.allowedOrigins = List.of(allowedOriginMain, allowedOriginWww);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        if (!isPublicEndpoint(request)) {
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                return onError(exchange, "Missing Authorization Header", HttpStatus.UNAUTHORIZED);
            }

            String authHeader = request.getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION).get(0);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return onError(exchange, "Invalid Authorization Header", HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(7);

            try {
                Claims claims = Jwts.parser()
                        .verifyWith(publicKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                String userId = claims.get("userId", String.class);

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

    private boolean isPublicEndpoint(ServerHttpRequest request) {
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return true;
        }

        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        final List<String> publicEndpoints = List.of(
                "/api/auth/register",
                "/api/auth/login",
                "/api/auth/send-otp",
                "/api/auth/verify-otp",
                "/api/auth/confirm-email",
                "/api/auth/refresh-token",
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/reviews",
                "/api/v1/reviews/**",
                "/api/v1/books",
                "/api/v1/books/**",
                "/api/v1/categories",
                "/api/v1/categories/**",
                "/api/v1/authors",
                "/api/v1/authors/**"
        );

        if (publicEndpoints.stream().anyMatch(uri -> pathMatcher.match(uri, path))) {
            return true;
        }

        if (isNewsPublicEndpoint(method, path)) {
            return true;
        }

        // promotion-service: public (khớp SecurityConfig + PromotionController)
        // Còn lại /api/v1/promotions* cần JWT; service kiểm tra ROLE_ADMIN
        return isPromotionPublicEndpoint(method, path);
    }

    /**
     * Guest (không JWT): chỉ đọc tin đã xuất bản — danh sách {@code /published} và chi tiết {@code /{uuid}}.
     * Mọi endpoint news khác (thống kê, advanced-search, CRUD, …) bắt buộc Bearer + header X-User-*.
     */
    private boolean isNewsPublicEndpoint(HttpMethod method, String path) {
        if (!HttpMethod.GET.equals(method)) {
            return false;
        }
        if (pathMatcher.match("/api/v1/news/published", path)) {
            return true;
        }
        if (!pathMatcher.match("/api/v1/news/*", path)) {
            return false;
        }
        String segment = path.substring("/api/v1/news/".length());
        return !segment.isEmpty() && !segment.contains("/") && NEWS_ID_PATH.matcher(segment).matches();
    }

    /**
     * Endpoint promotion không cần JWT tại gateway (đồng bộ promotion-service SecurityConfig).
     * <ul>
     *   <li>GET /active, /book/{bookId}</li>
     *   <li>POST /validate, /apply</li>
     * </ul>
     * Admin (POST/PUT/DELETE/PATCH/GET danh sách &amp; theo id) vẫn bắt buộc Bearer token.
     */
    private boolean isPromotionPublicEndpoint(HttpMethod method, String path) {
        if (HttpMethod.GET.equals(method)) {
            return pathMatcher.match("/api/v1/promotions/active", path)
                    || pathMatcher.match("/api/v1/promotions/book/**", path);
        }
        if (HttpMethod.POST.equals(method)) {
            return pathMatcher.match("/api/v1/promotions/validate", path)
                    || pathMatcher.match("/api/v1/promotions/apply", path);
        }
        return false;
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        addCorsHeaders(exchange, response);
        response.setStatusCode(httpStatus);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\": " + httpStatus.value() + ", \"message\": \"" + err + "\"}";
        byte[] bytes = body.getBytes();
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private void addCorsHeaders(ServerWebExchange exchange, ServerHttpResponse response) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin == null || !allowedOrigins.contains(origin)) {
            return;
        }

        HttpHeaders headers = response.getHeaders();
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE,OPTIONS,PATCH");
        headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
