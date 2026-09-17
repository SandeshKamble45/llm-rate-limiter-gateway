package com.sandesh.ratelimiter.quota;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class TenantQuotaSettlementIntegrationTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(
            DynamicPropertyRegistry registry) {

        registry.add(
                "spring.data.redis.host",
                redis::getHost);

        registry.add(
                "spring.data.redis.port",
                () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TenantQuotaService quotaService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void shouldRefundUnusedReservedBudget() {

        String tenantId =
                "settlement-refund-tenant";

        quotaService.clearForTesting(tenantId);

        TenantQuotaService.BudgetReservationResult reservation =
                quotaService.reserveBudget(
                        tenantId,
                        100_000);

        assertTrue(reservation.allowed());

        BudgetSettlementResult settlement =
                quotaService.settleBudget(
                        tenantId,
                        100_000,
                        60_000);

        assertEquals(
                100_000,
                settlement.reservedMicrodollars());

        assertEquals(
                60_000,
                settlement.actualMicrodollars());

        assertEquals(
                -40_000,
                settlement.adjustmentMicrodollars());

        assertEquals(
                60_000,
                settlement.spentMicrodollars());

        assertEquals(
                4_940_000,
                settlement.remainingMicrodollars());

        assertTrue(settlement.withinBudget());
    }

    @Test
    void shouldChargeAdditionalActualUsage() {

        String tenantId =
                "settlement-additional-tenant";

        quotaService.clearForTesting(tenantId);

        TenantQuotaService.BudgetReservationResult reservation =
                quotaService.reserveBudget(
                        tenantId,
                        100_000);

        assertTrue(reservation.allowed());

        BudgetSettlementResult settlement =
                quotaService.settleBudget(
                        tenantId,
                        100_000,
                        150_000);

        assertEquals(
                50_000,
                settlement.adjustmentMicrodollars());

        assertEquals(
                150_000,
                settlement.spentMicrodollars());

        assertEquals(
                4_850_000,
                settlement.remainingMicrodollars());

        assertTrue(settlement.withinBudget());
    }

    @Test
    void shouldNeverAllowSpentAmountToBecomeNegative() {

        String tenantId =
                "settlement-negative-tenant";

        quotaService.clearForTesting(tenantId);

        BudgetSettlementResult settlement =
                quotaService.settleBudget(
                        tenantId,
                        100_000,
                        0);

        assertEquals(
                0,
                settlement.spentMicrodollars());

        assertEquals(
                5_000_000,
                settlement.remainingMicrodollars());
    }
}