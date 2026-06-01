package com.notfound.bookstoreapigateway.filter;

import com.notfound.bookstoreapigateway.security.RsaKeyLoader;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpCookie;
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

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    private static final String ACCESS_TOKEN_COOKIE = "access_token";

    private static final Pattern NEWS_ID_PATH =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final PublicKey publicKey;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> allowedOrigins;

    public AuthenticationFilter(
            @Value("${app.jwt.keys-dir:/key}") String keysDir,
            @Value("${app.jwt.public-key-file:public.pem}") String publicKeyFile,
            @Value("${CORS_ALLOWED_ORIGIN_MAIN:https://nhasachcongdong.id.vn}") String allowedOriginMain,
            @Value("${CORS_ALLOWED_ORIGIN_WWW:https://www.nhasachcongdong.id.vn}") String allowedOriginWww,
            @Value("${CORS_ALLOWED_ORIGIN_LOCAL:http://localhost:3000}") String allowedOriginLocal) {
        Path dir = RsaKeyLoader.resolveKeysDir(keysDir);
        this.publicKey = RsaKeyLoader.loadPublicKey(dir, publicKeyFile);
        this.allowedOrigins = List.of(allowedOriginMain, allowedOriginWww, allowedOriginLocal);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();
        String origin = request.getHeaders().getOrigin();
        boolean hasAuthorizationHeader = request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION);
        boolean hasAccessTokenCookie = request.getCookies().containsKey(ACCESS_TOKEN_COOKIE);
        boolean publicEndpoint = isPublicEndpoint(request);

        log.debug(
                "Gateway auth request method={} path={} origin={} public={} hasAuthorizationHeader={} hasAccessTokenCookie={}",
                method,
                path,
                origin,
                publicEndpoint,
                hasAuthorizationHeader,
                hasAccessTokenCookie);

        if (!publicEndpoint) {
            String token = resolveBearerToken(request);
            if (token == null) {
                log.warn(
                        "Gateway auth rejected missing token method={} path={} origin={} hasAuthorizationHeader={} hasAccessTokenCookie={}",
                        method,
                        path,
                        origin,
                        hasAuthorizationHeader,
                        hasAccessTokenCookie);
                return onError(exchange, "Missing Authorization Header", HttpStatus.UNAUTHORIZED);
            }

            try {
                Claims claims = Jwts.parser()
                        .verifyWith(publicKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                String userId = claims.get("userId", String.class);
                boolean reviewRoute = pathMatcher.match("/api/v1/reviews/**", path);

                ServerHttpRequest mutatedRequest = request.mutate()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Name", username != null ? username : "")
                        .header("X-User-Email", username != null ? username : "")
                        .header("X-User-Role", role != null ? role : "")
                        .header("X-User-Id", userId != null ? userId : "")
                        .build();

                log.debug(
                        "Gateway auth accepted method={} path={} reviewRoute={} userId={} role={} subject={}",
                        method,
                        path,
                        reviewRoute,
                        mask(userId),
                        role,
                        username);

                return chain.filter(exchange.mutate().request(mutatedRequest).build());

            } catch (Exception e) {
                log.warn(
                        "Gateway auth rejected invalid token method={} path={} origin={} error={}",
                        method,
                        path,
                        origin,
                        e.getMessage());
                return onError(exchange, "Token invalid or expired: " + e.getMessage(), HttpStatus.UNAUTHORIZED);
            }
        }

        log.debug("Gateway auth skipped public endpoint method={} path={} origin={}", method, path, origin);
        return chain.filter(exchange);
    }

    private String resolveBearerToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            return token.isEmpty() ? null : token;
        }

        HttpCookie accessTokenCookie = request.getCookies().getFirst(ACCESS_TOKEN_COOKIE);
        if (accessTokenCookie == null) {
            return null;
        }

        String token = accessTokenCookie.getValue();
        return token == null || token.isBlank() ? null : token.trim();
    }

    private boolean isPublicEndpoint(ServerHttpRequest request) {
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return true;
        }

        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        final List<String> publicEndpoints = List.of(
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/auth/google/callback",
                "/api/auth/google/callback",
                "/api/v1/auth/send-otp",
                "/api/v1/auth/verify-otp",
                "/api/v1/auth/verify-email",
                "/api/v1/auth/confirm-email",
                "/api/v1/auth/refresh-token",
                "/api/v1/auth/logout",
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

        if (isReviewPublicEndpoint(method, path)) {
            return true;
        }

        // promotion-service: public (khớp SecurityConfig + PromotionController)
        // Còn lại /api/v1/promotions* cần JWT; service kiểm tra ROLE_ADMIN
        if (isPaymentGatewayEndpoint(method, path)) {
            return true;
        }

        return isPromotionPublicEndpoint(method, path);
    }

    /**
     * Guest (không JWT): chỉ đọc tin đã xuất bản, xem popular tags, và ghi nhận tag search.
     * Mọi endpoint news khác (thống kê, advanced-search, CRUD, …) bắt buộc Bearer + header X-User-*.
     */
    private boolean isNewsPublicEndpoint(HttpMethod method, String path) {
        if (HttpMethod.GET.equals(method)) {
            if (pathMatcher.match("/api/v1/news/published", path)
                    || pathMatcher.match("/api/v1/news/popular-tags", path)) {
                return true;
            }
            if (!pathMatcher.match("/api/v1/news/*", path)) {
                return false;
            }
            String segment = path.substring("/api/v1/news/".length());
            return !segment.isEmpty() && !segment.contains("/") && NEWS_ID_PATH.matcher(segment).matches();
        }

        if (HttpMethod.POST.equals(method)) {
            return pathMatcher.match("/api/v1/news/tag-searches", path);
        }

        return false;
    }

    /**
     * Endpoint promotion không cần JWT tại gateway (đồng bộ promotion-service SecurityConfig).
     * <ul>
     *   <li>GET /active, /book/{bookId}</li>
     *   <li>POST /validate, /apply</li>
     * </ul>
     * Admin (POST/PUT/DELETE/PATCH/GET danh sách &amp; theo id) vẫn bắt buộc Bearer token.
     */
    /** Guest: đọc review theo sách; gửi review (POST add) bắt buộc Bearer JWT. */
    private boolean isReviewPublicEndpoint(HttpMethod method, String path) {
        if (!HttpMethod.GET.equals(method)) {
            return false;
        }
        return pathMatcher.match("/api/v1/reviews/book/**", path)
                || pathMatcher.match("/api/v1/reviews/users/**/count", path);
    }

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

    private boolean isPaymentGatewayEndpoint(HttpMethod method, String path) {
        if (HttpMethod.GET.equals(method)) {
            return pathMatcher.match("/api/v1/payment/vnpay/callback", path)
                    || pathMatcher.match("/api/v1/payment/zalopay/return", path)
                    || pathMatcher.match("/api/v1/payment/momo/return", path);
        }
        if (HttpMethod.POST.equals(method)) {
            return pathMatcher.match("/api/v1/payment/zalopay/callback", path)
                    || pathMatcher.match("/api/v1/payment/momo/callback", path);
        }
        return false;
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        ServerHttpRequest request = exchange.getRequest();
        log.warn(
                "Gateway auth response status={} method={} path={} message={}",
                httpStatus.value(),
                request.getMethod(),
                request.getURI().getPath(),
                err);
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
            if (origin != null) {
                log.warn(
                        "Gateway auth error response without CORS headers origin={} allowedOrigins={}",
                        origin,
                        allowedOrigins);
            }
            return;
        }

        HttpHeaders headers = response.getHeaders();
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE,OPTIONS,PATCH");
        headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "***";
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
