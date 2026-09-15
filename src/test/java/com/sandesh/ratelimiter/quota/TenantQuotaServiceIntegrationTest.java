package com.sandesh.ratelimiter.quota;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
class TenantQuotaServiceIntegrationTest {

    @Container
    static final RedisContainer redis =
            new RedisContainer(
                    DockerImageName.parse("redis:7-alpine")
            );

    @Autowired
    private TenantQuotaService quotaService;

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
        quotaService.clearForTesting("test-tenant");
    }

    @Test
    void shouldAllowReservationWhenWithinDailyBudget() {

        TenantQuotaService.BudgetReservationResult result =
                quotaService.reserveBudget(
                        "test-tenant",
                        1_000_000
                );

        assertTrue(result.allowed());

        assertEquals(
                1_000_000,
                result.spentMicrodollars()
        );

        assertEquals(
                4_000_000,
                result.remainingMicrodollars()
        );
    }

    @Test
    void shouldRejectReservationWhenBudgetWouldBeExceeded() {

        TenantQuotaService.BudgetReservationResult first =
                quotaService.reserveBudget(
                        "test-tenant",
                        4_000_000
                );

        assertTrue(first.allowed());

        TenantQuotaService.BudgetReservationResult second =
                quotaService.reserveBudget(
                        "test-tenant",
                        2_000_000
                );

        assertFalse(second.allowed());

        assertEquals(
                4_000_000,
                second.spentMicrodollars()
        );

        assertEquals(
                1_000_000,
                second.remainingMicrodollars()
        );
    }
}