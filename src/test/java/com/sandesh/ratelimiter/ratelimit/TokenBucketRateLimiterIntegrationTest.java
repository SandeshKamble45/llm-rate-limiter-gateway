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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "ratelimit.token-bucket.capacity=100",
        "ratelimit.token-bucket.refill-rate-per-sec=10"
})
class TokenBucketRateLimiterIntegrationTest {

    @Container
    static final RedisContainer redis =
            new RedisContainer(
                    DockerImageName.parse("redis:7-alpine")
            );

    @Autowired
    private TokenBucketRateLimiter rateLimiter;

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
        redisTemplate.delete("ratelimit:tb:test-tenant");
        redisTemplate.delete("ratelimit:tb:retry-test-tenant");
    }

    @Test
    void shouldAllowRequestWhenEnoughTokensAreAvailable() {

        RateLimitResult result =
                rateLimiter.tryConsume("test-tenant", 50);

        assertTrue(result.allowed());
        assertEquals("token-bucket", result.algorithm());

        assertTrue(
                result.remainingTokens() >= 49
        );

        assertEquals(
                0,
                result.retryAfterSeconds()
        );
    }

    @Test
    void shouldConsumeRequestedTokens() {

        RateLimitResult first =
                rateLimiter.tryConsume("test-tenant", 30);

        RateLimitResult second =
                rateLimiter.tryConsume("test-tenant", 20);

        assertTrue(first.allowed());
        assertTrue(second.allowed());

        assertTrue(
                second.remainingTokens()
                        < first.remainingTokens()
        );
    }

    @Test
    void shouldRejectRequestWhenCostExceedsBucketCapacity() {

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> rateLimiter.tryConsume(
                                "test-tenant",
                                101
                        )
                );

        assertEquals(
                "Requested token cost cannot exceed bucket capacity",
                exception.getMessage()
        );
    }

    @Test
    void shouldReturnRetryAfterWhenNotEnoughTokensAreAvailable() {

        RateLimitResult first =
                rateLimiter.tryConsume(
                        "retry-test-tenant",
                        90
                );

        assertTrue(first.allowed());

        assertEquals(
                10.0,
                first.remainingTokens(),
                0.5
        );

        RateLimitResult second =
                rateLimiter.tryConsume(
                        "retry-test-tenant",
                        30
                );

        assertFalse(second.allowed());

        assertEquals(
                "token-bucket",
                second.algorithm()
        );

        assertTrue(
                second.remainingTokens() < 30
        );

        /*
         * 20 tokens are missing:
         *
         * requested = 30
         * available ≈ 10
         * missing   ≈ 20
         *
         * refill rate = 10 tokens/sec
         *
         * retry-after ≈ 20 / 10 = 2 seconds
         */
        assertTrue(
                second.retryAfterSeconds() >= 1
                        && second.retryAfterSeconds() <= 3
        );
    }
}