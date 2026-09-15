package com.sandesh.ratelimiter.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Controls the tenant's daily spending budget.
 *
 * The important property of this service is that budget admission is atomic:
 *
 *     read current spend
 *          +
 *     check against budget
 *          +
 *     reserve the requested amount
 *
 * happen inside one Redis Lua script.
 *
 * This prevents concurrent gateway instances from both passing the same
 * budget check and overspending the tenant's daily quota.
 */
@Service
public class TenantQuotaService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<List> budgetReservationScript;

    /**
     * Default daily budget: $5.
     *
     * Stored as microdollars:
     *
     *     $1 = 1,000,000 microdollars
     *     $5 = 5,000,000 microdollars
     *
     * Integer accounting avoids floating-point money calculations.
     */
    @Value("${quota.daily-budget-microdollars:5000000}")
    private long defaultDailyBudgetMicrodollars;

    /**
     * Keep the daily key alive slightly longer than 24 hours so that
     * it survives the UTC day boundary without being retained forever.
     */
    private static final long DAILY_KEY_TTL_SECONDS = 26 * 60 * 60;

    public TenantQuotaService(
            RedisTemplate<String, Object> redisTemplate,
            DefaultRedisScript<List> budgetReservationScript) {
        this.redisTemplate = redisTemplate;
        this.budgetReservationScript = budgetReservationScript;
    }

    /**
     * Atomically reserves budget for a request.
     *
     * @param tenantId tenant whose budget is being consumed
     * @param requestedMicrodollars maximum expected cost of this request
     * @return result describing whether the reservation succeeded
     */
    public BudgetReservationResult reserveBudget(
            String tenantId,
            long requestedMicrodollars) {

        if (requestedMicrodollars <= 0) {
            throw new IllegalArgumentException(
                    "Requested budget must be greater than zero");
        }

        String key = dailyKey(tenantId);

        List<Long> result = redisTemplate.execute(
                budgetReservationScript,
                List.of(key),
                String.valueOf(requestedMicrodollars),
                String.valueOf(defaultDailyBudgetMicrodollars),
                String.valueOf(DAILY_KEY_TTL_SECONDS)
        );

        boolean allowed = result.get(0) == 1L;
        long spentMicrodollars = result.get(1);

        long remainingMicrodollars = Math.max(
                0,
                defaultDailyBudgetMicrodollars - spentMicrodollars
        );

        return new BudgetReservationResult(
                allowed,
                spentMicrodollars,
                remainingMicrodollars
        );
    }

    /**
     * Records additional actual usage after the LLM call.
     *
     * This method will be replaced by proper reservation settlement once
     * provider-specific token pricing is introduced.
     */
    public void recordAdditionalUsage(
            String tenantId,
            long additionalMicrodollars) {

        if (additionalMicrodollars <= 0) {
            return;
        }

        String key = dailyKey(tenantId);

        redisTemplate.opsForValue().increment(
                key,
                additionalMicrodollars
        );

        redisTemplate.expire(
                key,
                Duration.ofSeconds(DAILY_KEY_TTL_SECONDS)
        );
    }

    private String dailyKey(String tenantId) {
        LocalDate utcDate = LocalDate.now(ZoneOffset.UTC);

        return "quota:daily:"
                + tenantId
                + ":"
                + utcDate;
    }

    void clearForTesting(String tenantId) {
         redisTemplate.delete(dailyKey(tenantId));
    }

    public record BudgetReservationResult(
            boolean allowed,
            long spentMicrodollars,
            long remainingMicrodollars) {
    }
}