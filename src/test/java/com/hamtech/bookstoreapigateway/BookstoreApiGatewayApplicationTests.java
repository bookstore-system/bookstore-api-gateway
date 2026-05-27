package com.notfound.bookstoreapigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notfound.bookstoreapigateway.filter.AuthRateLimitFilter;
import com.notfound.bookstoreapigateway.filter.UserTokenBucketRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookstoreApiGatewayApplicationTests {

    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);

    @Test
    @SuppressWarnings("unchecked")
    void authLoginUsesUsernameSlidingWindowAndForwardsAllowedRequest() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(1L));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(redisTemplate, new ObjectMapper());
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        GatewayFilterChain chain = exchange -> {
            forwardedExchange.set(exchange);
            return Mono.empty();
        };
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"username\":\"Alice\",\"password\":\"secret\"}")
        );

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwardedExchange.get()).isNotNull();
        String forwardedBody = readBody(forwardedExchange.get());
        assertThat(forwardedBody).contains("\"username\":\"Alice\"");
        verify(redisTemplate).execute(
                any(RedisScript.class),
                org.mockito.ArgumentMatchers.argThat(keys ->
                        ((List<String>) keys).stream().anyMatch(key -> key.contains("login:alice"))),
                org.mockito.ArgumentMatchers.argThat(args -> ((List<String>) args).get(0).equals("5"))
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void authSendOtpUsesEmailSlidingWindowAndBlocksOverLimitRequest() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(0L));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(redisTemplate, new ObjectMapper());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/send-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"email\":\"User@Test.COM\"}")
        );

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, never()).filter(any(ServerWebExchange.class));
        verify(redisTemplate).execute(
                any(RedisScript.class),
                org.mockito.ArgumentMatchers.argThat(keys ->
                        ((List<String>) keys).stream().anyMatch(key -> key.contains("send-otp:user@test.com"))),
                org.mockito.ArgumentMatchers.argThat(args -> ((List<String>) args).get(0).equals("3"))
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void authRegisterUsesUsernameSlidingWindowLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(1L));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(redisTemplate, new ObjectMapper());
        GatewayFilterChain chain = exchange -> Mono.empty();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"username\":\"Bob\",\"email\":\"bob@test.com\",\"password\":\"secret\"}")
        );

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                org.mockito.ArgumentMatchers.argThat(keys ->
                        ((List<String>) keys).stream().anyMatch(key -> key.contains("register:bob"))),
                org.mockito.ArgumentMatchers.argThat(args -> ((List<String>) args).get(0).equals("5"))
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalAuthenticatedApiUsesUserTokenBucket() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(1L));
        UserTokenBucketRateLimitFilter filter = new UserTokenBucketRateLimitFilter(redisTemplate);
        GatewayFilterChain chain = exchange -> Mono.empty();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/cart")
                        .header("X-User-Id", "USER-123")
        );

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                org.mockito.ArgumentMatchers.argThat(keys ->
                        ((List<String>) keys).stream().anyMatch(key -> key.contains("user:user-123"))),
                org.mockito.ArgumentMatchers.argThat(args -> {
                    List<String> values = (List<String>) args;
                    return values.get(0).equals("5") && values.get(1).equals("100");
                })
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiApiUsesLowerTokenBucketAndBlocksOverLimitRequest() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(0L));
        UserTokenBucketRateLimitFilter filter = new UserTokenBucketRateLimitFilter(redisTemplate);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/chatbot/chat/message")
                        .header("X-User-Id", "USER-456")
        );

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, never()).filter(any(ServerWebExchange.class));
        verify(redisTemplate).execute(
                any(RedisScript.class),
                org.mockito.ArgumentMatchers.argThat(keys ->
                        ((List<String>) keys).stream().anyMatch(key -> key.contains("ai:user:user-456"))),
                org.mockito.ArgumentMatchers.argThat(args -> {
                    List<String> values = (List<String>) args;
                    return values.get(0).equals("1") && values.get(1).equals("20");
                })
        );
    }

    @Test
    void tokenBucketSkipsAnonymousRequests() {
        UserTokenBucketRateLimitFilter filter = new UserTokenBucketRateLimitFilter(redisTemplate);
        GatewayFilterChain chain = exchange -> Mono.empty();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/books")
        );

        filter.filter(exchange, chain).block();

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyList());
    }

    private String readBody(ServerWebExchange exchange) {
        return exchange.getRequest().getBody()
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    return new String(bytes, StandardCharsets.UTF_8);
                })
                .collectList()
                .map(parts -> String.join("", parts))
                .block();
    }
}
