package com.sandesh.ratelimiter.ratelimit;

import com.sandesh.ratelimiter.model.RateLimitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Token-based limiting: cost of a request is the number of prompt+completion
 * tokens it consumes, not "1 request". This is what actually matches an LLM's
 * real resource cost — a 4000-token prompt is not the same cost as a 10-token one.
 */
@Service
public class TokenBucketRateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;

    @Value("${ratelimit.token-bucket.capacity:10000}")
    private long capacity;

    @Value("${ratelimit.token-bucket.refill-rate-per-sec:50}")
    private double refillRatePerSec;

    public TokenBucketRateLimiter(RedisTemplate<String, Object> redisTemplate,
                                   DefaultRedisScript<List> tokenBucketScript) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
    }

    /**
     * @param tenantKey     unique id, e.g. "tenant123:gpt-4o"
     * @param requestedCost tokens this request will consume (estimate prompt now,
     *                      true up with actual usage after the LLM call returns)
     */
    public RateLimitResult tryConsume(String tenantKey, long requestedCost) {
        String redisKey = "ratelimit:tb:" + tenantKey;
        double now = System.currentTimeMillis() / 1000.0;

        List<Long> result = redisTemplate.execute(
                tokenBucketScript,
                List.of(redisKey),
                String.valueOf(capacity),
                String.valueOf(refillRatePerSec),
                String.valueOf(requestedCost),
                String.valueOf(now)
        );

        boolean allowed = result.get(0) == 1L;
        double remaining = result.get(1);
        return new RateLimitResult(allowed, remaining, "token-bucket");
    }
}
