package com.notfound.bookstoreapigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Component
public class UserTokenBucketRateLimitFilter implements GlobalFilter, Ordered {

    private static final RedisScript<Long> TOKEN_BUCKET_SCRIPT = RedisScript.of("""
            local tokens_key = KEYS[1]
            local timestamp_key = KEYS[2]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local ttl = tonumber(ARGV[5])

            local last_tokens = tonumber(redis.call('get', tokens_key))
            if last_tokens == nil then
                last_tokens = capacity
            end

            local last_refreshed = tonumber(redis.call('get', timestamp_key))
            if last_refreshed == nil then
                last_refreshed = now
            end

            local delta = math.max(0, now - last_refreshed)
            local filled_tokens = math.min(capacity, last_tokens + (delta * rate))
            local allowed = filled_tokens >= requested
            local new_tokens = filled_tokens

            if allowed then
                new_tokens = filled_tokens - requested
            end

            redis.call('setex', tokens_key, ttl, new_tokens)
            redis.call('setex', timestamp_key, ttl, now)

            if allowed then
                return 1
            end
            return 0
            """, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;

    public UserTokenBucketRateLimitFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (userId == null || userId.isBlank()) {
            return chain.filter(exchange);
        }

        BucketRule rule = resolveRule(request);
        if (rule == null) {
            return chain.filter(exchange);
        }

        String userKey = normalize(userId);
        String keyTag = rule.name() + ":user:" + userKey;
        List<String> keys = List.of(
                "rate:bucket:{" + keyTag + "}:tokens",
                "rate:bucket:{" + keyTag + "}:ts"
        );
        List<String> args = List.of(
                String.valueOf(rule.replenishRate()),
                String.valueOf(rule.burstCapacity()),
                String.valueOf(Instant.now().getEpochSecond()),
                "1",
                String.valueOf(rule.ttlSeconds())
        );

        return redisTemplate.execute(TOKEN_BUCKET_SCRIPT, keys, args)
                .next()
                .defaultIfEmpty(1L)
                .onErrorReturn(1L)
                .flatMap(allowed -> {
                    if (allowed == 1L) {
                        return chain.filter(exchange);
                    }
                    return tooManyRequests(exchange, "Too many requests");
                });
    }

    private BucketRule resolveRule(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        if (path.startsWith("/api/v1/ai/") || path.startsWith("/api/v1/chatbot/chat")) {
            return new BucketRule("ai", 1, 20, 120);
        }
        if (isAuthEndpoint(path) || isPaymentGatewayCallback(path)) {
            return null;
        }
        return new BucketRule("user", 5, 100, 120);
    }

    private boolean isAuthEndpoint(String path) {
        return path.startsWith("/api/v1/auth/");
    }

    private boolean isPaymentGatewayCallback(String path) {
        return path.startsWith("/api/v1/payment/vnpay/callback")
                || path.startsWith("/api/v1/payment/zalopay/return")
                || path.startsWith("/api/v1/payment/zalopay/callback")
                || path.startsWith("/api/v1/payment/momo/return")
                || path.startsWith("/api/v1/payment/momo/callback");
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = ("{\"code\": 429, \"message\": \"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9@._-]", "_");
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private record BucketRule(String name, int replenishRate, int burstCapacity, int ttlSeconds) {
    }
}
