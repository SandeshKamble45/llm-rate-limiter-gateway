package com.sandesh.ratelimiter.ratelimit;

import com.sandesh.ratelimiter.model.RateLimitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Exact sliding-window log.
 *
 * Unlike token bucket, this algorithm stores individual request timestamps
 * inside a Redis sorted set. This gives precise request-count enforcement
 * over the configured window at the cost of O(number of requests in the
 * window) memory per tenant.
 */
@Service
public class SlidingWindowRateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<List> slidingWindowScript;

    @Value("${ratelimit.sliding-window.window-seconds:60}")
    private long windowSeconds;

    @Value("${ratelimit.sliding-window.max-requests:100}")
    private long maxRequests;

    public SlidingWindowRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            DefaultRedisScript<List> slidingWindowScript) {

        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = slidingWindowScript;
    }

    public RateLimitResult tryConsume(String tenantKey) {

        String redisKey = "ratelimit:sw:" + tenantKey;

        String requestId = UUID.randomUUID().toString();

        List<Long> result = redisTemplate.execute(
                slidingWindowScript,
                List.of(redisKey),
                String.valueOf(windowSeconds),
                String.valueOf(maxRequests),
                requestId
        );

        boolean allowed = result.get(0) == 1L;
        long currentCount = result.get(1);
        long retryAfterSeconds = result.get(2);

        long remainingRequests = Math.max(
                0,
                maxRequests - currentCount
        );

        return new RateLimitResult(
                allowed,
                remainingRequests,
                "sliding-window",
                retryAfterSeconds
        );
    }
}