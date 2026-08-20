package com.sandesh.ratelimiter.quota;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Cost-based cap, separate from the rate limiter. A tenant can be within
 * their rate limit (requests/tokens per second) but still blow past their
 * daily spend budget on an expensive model — this is the layer that catches
 * that. Track it independently so you can explain in interviews why these
 * are two different concerns: burst control vs budget control.
 */
@Service
public class TenantQuotaService {

    private final RedisTemplate<String, Object> redisTemplate;

    // cost per 1K tokens in USD cents — expand this map as you add models
    private static final double COST_PER_1K_TOKENS_CENTS = 1.5;

    public TenantQuotaService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean withinDailyBudget(String tenantId, double dailyBudgetCents) {
        String key = dailyKey(tenantId);
        Object spentObj = redisTemplate.opsForValue().get(key);
        double spent = spentObj == null ? 0.0 : Double.parseDouble(spentObj.toString());
        return spent < dailyBudgetCents;
    }

    public void recordUsage(String tenantId, int tokensUsed) {
        String key = dailyKey(tenantId);
        double costCents = (tokensUsed / 1000.0) * COST_PER_1K_TOKENS_CENTS;
        redisTemplate.opsForValue().increment(key, costCents);
        redisTemplate.expire(key, java.time.Duration.ofHours(26));
    }

    private String dailyKey(String tenantId) {
        return "quota:daily:" + tenantId + ":" + LocalDate.now();
    }
}
