package com.notfound.bookstoreapigateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class AuthRateLimitFilter implements GlobalFilter, Ordered {

    private static final int WINDOW_SECONDS = 60;
    private static final int BUCKET_SECONDS = 10;
    private static final int BUCKET_COUNT = WINDOW_SECONDS / BUCKET_SECONDS;
    private static final int KEY_TTL_SECONDS = WINDOW_SECONDS + BUCKET_SECONDS;

    private static final RedisScript<Long> SLIDING_COUNTER_SCRIPT = RedisScript.of("""
            local total = 0
            for i = 1, #KEYS do
                total = total + tonumber(redis.call('get', KEYS[i]) or '0')
            end
            if total >= tonumber(ARGV[1]) then
                return 0
            end
            redis.call('incr', KEYS[1])
            redis.call('expire', KEYS[1], tonumber(ARGV[2]))
            return 1
            """, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        AuthLimitRule rule = resolveRule(request);
        if (rule == null) {
            return chain.filter(exchange);
        }

        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] body = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(body);
                    DataBufferUtils.release(dataBuffer);

                    String identity = extractIdentity(body, rule.identityField());
                    if (identity == null) {
                        return chain.filter(exchange.mutate()
                                .request(decorate(request, body, exchange.getResponse().bufferFactory()))
                                .build());
                    }

                    List<String> keys = buildCounterKeys(rule.name(), identity);
                    List<String> args = List.of(String.valueOf(rule.limit()), String.valueOf(KEY_TTL_SECONDS));
                    return redisTemplate.execute(SLIDING_COUNTER_SCRIPT, keys, args)
                            .next()
                            .defaultIfEmpty(1L)
                            .onErrorReturn(1L)
                            .flatMap(allowed -> {
                                if (allowed == 1L) {
                                    return chain.filter(exchange.mutate()
                                            .request(decorate(request, body, exchange.getResponse().bufferFactory()))
                                            .build());
                                }
                                return tooManyRequests(exchange, "Too many auth requests");
                            });
                });
    }

    private AuthLimitRule resolveRule(ServerHttpRequest request) {
        if (!HttpMethod.POST.equals(request.getMethod())) {
            return null;
        }
        String path = request.getURI().getPath();
        if ("/api/v1/auth/login".equals(path)) {
            return new AuthLimitRule("login", "username", 5);
        }
        if ("/api/v1/auth/send-otp".equals(path)) {
            return new AuthLimitRule("send-otp", "email", 3);
        }
        if ("/api/v1/auth/register".equals(path)) {
            return new AuthLimitRule("register", "username", 5);
        }
        return null;
    }

    private String extractIdentity(byte[] body, String fieldName) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode field = root.get(fieldName);
            if (field == null || field.asText().isBlank()) {
                return null;
            }
            return normalize(field.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> buildCounterKeys(String ruleName, String identity) {
        long currentBucket = Instant.now().getEpochSecond() / BUCKET_SECONDS;
        List<String> keys = new ArrayList<>(BUCKET_COUNT);
        String keyTag = ruleName + ":" + identity;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            keys.add("rate:auth:{" + keyTag + "}:bucket:" + (currentBucket - i));
        }
        return keys;
    }

    private ServerHttpRequest decorate(ServerHttpRequest request, byte[] body, DataBufferFactory bufferFactory) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Mono.just(bufferFactory.wrap(body)));
            }
        };
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
        return -2;
    }

    private record AuthLimitRule(String name, String identityField, int limit) {
    }
}
