package com.sandesh.ratelimiter.ratelimit;

import com.redis.testcontainers.RedisContainer;
import com.sandesh.ratelimiter.model.RateLimitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "ratelimit.sliding-window.window-seconds=2",
        "ratelimit.sliding-window.max-requests=3"
})
class SlidingWindowRateLimiterIntegrationTest {

    @Container
    static final RedisContainer redis =
            new RedisContainer(
                    DockerImageName.parse("redis:7-alpine")
            );

    @Autowired
    private SlidingWindowRateLimiter rateLimiter;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.data.redis.host",
                redis::getHost
        );

        registry.add(
                "spring.data.redis.port",
                redis::getFirstMappedPort
        );
    }

    @BeforeEach
    void cleanRedis() {
        redisTemplate.delete("ratelimit:sw:test-tenant");
        redisTemplate.delete("ratelimit:sw:full-window-tenant");
        redisTemplate.delete("ratelimit:sw:expiration-tenant");
    }

    @Test
    void shouldAllowRequestWhenWindowHasCapacity() {

        RateLimitResult result =
                rateLimiter.tryConsume("test-tenant");

        assertTrue(result.allowed());
        assertEquals(
                "sliding-window",
                result.algorithm()
        );

        assertEquals(
                2.0,
                result.remainingTokens()
        );

        assertEquals(
                0,
                result.retryAfterSeconds()
        );
    }

    @Test
    void shouldCountRequestsWithinWindow() {

        RateLimitResult first =
                rateLimiter.tryConsume("test-tenant");

        RateLimitResult second =
                rateLimiter.tryConsume("test-tenant");

        RateLimitResult third =
                rateLimiter.tryConsume("test-tenant");

        assertTrue(first.allowed());
        assertTrue(second.allowed());
        assertTrue(third.allowed());

        assertEquals(2.0, first.remainingTokens());
        assertEquals(1.0, second.remainingTokens());
        assertEquals(0.0, third.remainingTokens());
    }

    @Test
    void shouldRejectRequestWhenWindowIsFull() {

        rateLimiter.tryConsume("full-window-tenant");
        rateLimiter.tryConsume("full-window-tenant");
        rateLimiter.tryConsume("full-window-tenant");

        RateLimitResult fourth =
                rateLimiter.tryConsume("full-window-tenant");

        assertFalse(fourth.allowed());

        assertEquals(
                "sliding-window",
                fourth.algorithm()
        );

        assertEquals(
                0.0,
                fourth.remainingTokens()
        );

        assertTrue(
                fourth.retryAfterSeconds() >= 1
                        && fourth.retryAfterSeconds() <= 2
        );
    }

    @Test
    void shouldAllowRequestAfterWindowExpires()
            throws InterruptedException {

        rateLimiter.tryConsume("expiration-tenant");
        rateLimiter.tryConsume("expiration-tenant");
        rateLimiter.tryConsume("expiration-tenant");

        RateLimitResult rejected =
                rateLimiter.tryConsume("expiration-tenant");

        assertFalse(rejected.allowed());

        Thread.sleep(2200);

        RateLimitResult allowed =
                rateLimiter.tryConsume("expiration-tenant");

        assertTrue(allowed.allowed());

        assertEquals(
                "sliding-window",
                allowed.algorithm()
        );
    }
}