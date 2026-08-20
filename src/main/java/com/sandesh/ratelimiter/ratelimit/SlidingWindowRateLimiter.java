package com.sandesh.ratelimiter.ratelimit;

import com.sandesh.ratelimiter.model.RateLimitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Exact sliding window log. Use this to demonstrate the trade-off in your
 * README/benchmark: precise but O(window_size) memory per key, vs token
 * bucket's O(1) memory but smoothed/approximate burst behaviour.
 */
@Service
public class SlidingWindowRateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<List> slidingWindowScript;

    @Value("${ratelimit.sliding-window.window-seconds:60}")
    private long windowSeconds;

    @Value("${ratelimit.sliding-window.max-requests:100}")
    private long maxRequests;

    public SlidingWindowRateLimiter(RedisTemplate<String, Object> redisTemplate,
                                     DefaultRedisScript<List> slidingWindowScript) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = slidingWindowScript;
    }

    public RateLimitResult tryConsume(String tenantKey) {
        String redisKey = "ratelimit:sw:" + tenantKey;
        long now = System.currentTimeMillis();

        List<Long> result = redisTemplate.execute(
                slidingWindowScript,
                List.of(redisKey),
                String.valueOf(windowSeconds),
                String.valueOf(maxRequests),
                String.valueOf(now)
        );

        boolean allowed = result.get(0) == 1L;
        double currentCount = result.get(1);
        return new RateLimitResult(allowed, maxRequests - currentCount, "sliding-window");
    }
}
