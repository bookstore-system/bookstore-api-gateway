package com.notfound.bookstoreapigateway.filter;

import com.notfound.bookstoreapigateway.security.RsaKeyLoader;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.function.Predicate;

/**
 * Gateway Authentication Filter sử dụng RS256.
 * Public key đọc từ {@code /key/public.pem} (cùng cặp key với user-service).
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    private final PublicKey publicKey;

    public AuthenticationFilter(
            @Value("${app.jwt.keys-dir:/key}") String keysDir,
            @Value("${app.jwt.public-key-file:public.pem}") String publicKeyFile) {
        Path dir = RsaKeyLoader.resolveKeysDir(keysDir);
        this.publicKey = RsaKeyLoader.loadPublicKey(dir, publicKeyFile);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        final List<String> publicEndpoints = List.of(
                "/api/auth/register",
                "/api/auth/login",
                "/api/auth/send-otp",
                "/api/auth/verify-otp",
                "/api/auth/confirm-email",
                "/api/auth/refresh-token",
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/promotions/**",
                "/api/v1/reviews/**",
                "/api/v1/books/**",
                "/api/v1/categories/**",
                "/api/v1/authors/**"
        );

        Predicate<ServerHttpRequest> isApiSecured = r -> publicEndpoints.stream()
                .noneMatch(uri -> r.getURI().getPath().contains(uri));

        if (isApiSecured.test(request)) {
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
        return -1;
    }
}
