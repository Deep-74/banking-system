package com.banking.apigatewayservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterConfigTest {

    private final KeyResolver keyResolver = new RateLimiterConfig().keyResolver();

    @Test
    void keyResolver_usesClientIpAsKey() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/accounts/123")
                .remoteAddress(new InetSocketAddress("10.1.2.3", 5000))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String key = keyResolver.resolve(exchange).block();

        assertThat(key).isEqualTo("10.1.2.3");
    }

    @Test
    void keyResolver_differentClientsGetDifferentKeys() {
        MockServerWebExchange first = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/accounts/1")
                        .remoteAddress(new InetSocketAddress("10.0.0.1", 1000))
                        .build());
        MockServerWebExchange second = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/accounts/1")
                        .remoteAddress(new InetSocketAddress("10.0.0.2", 1000))
                        .build());

        assertThat(keyResolver.resolve(first).block())
                .isNotEqualTo(keyResolver.resolve(second).block());
    }

    @Test
    void keyResolver_sameClientSameKeyRegardlessOfPort() {
        MockServerWebExchange a = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x")
                        .remoteAddress(new InetSocketAddress("10.0.0.9", 1111))
                        .build());
        MockServerWebExchange b = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x")
                        .remoteAddress(new InetSocketAddress("10.0.0.9", 2222))
                        .build());

        assertThat(keyResolver.resolve(a).block())
                .isEqualTo(keyResolver.resolve(b).block());
    }
}

