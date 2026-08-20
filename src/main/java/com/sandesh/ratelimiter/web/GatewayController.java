package com.sandesh.ratelimiter.web;

import com.sandesh.ratelimiter.llm.LlmProviderClient;
import com.sandesh.ratelimiter.model.RateLimitResult;
import com.sandesh.ratelimiter.quota.TenantQuotaService;
import com.sandesh.ratelimiter.ratelimit.SlidingWindowRateLimiter;
import com.sandesh.ratelimiter.ratelimit.TokenBucketRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/gateway")
public class GatewayController {

    private final TokenBucketRateLimiter tokenBucketLimiter;
    private final SlidingWindowRateLimiter slidingWindowLimiter;
    private final TenantQuotaService quotaService;
    private final LlmProviderClient llmProviderClient;

    public GatewayController(TokenBucketRateLimiter tokenBucketLimiter,
                              SlidingWindowRateLimiter slidingWindowLimiter,
                              TenantQuotaService quotaService,
                              LlmProviderClient llmProviderClient) {
        this.tokenBucketLimiter = tokenBucketLimiter;
        this.slidingWindowLimiter = slidingWindowLimiter;
        this.quotaService = quotaService;
        this.llmProviderClient = llmProviderClient;
    }

    public record ChatRequest(String tenantId, String prompt) {}

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        String tenantKey = request.tenantId() + ":default-model";

        // Layer 1: daily cost budget — cheapest check, fail fast
        if (!quotaService.withinDailyBudget(request.tenantId(), 500.0)) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body("Daily budget exceeded for tenant " + request.tenantId());
        }

        // Layer 2: token-based rate limit — estimate cost before calling the model
        long estimatedTokens = Math.max(1, request.prompt().length() / 4);
        RateLimitResult limitResult = tokenBucketLimiter.tryConsume(tenantKey, estimatedTokens);

        if (!limitResult.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .body(limitResult);
        }

        // Layer 3: circuit-breaker-protected call with automatic fallback
        LlmProviderClient.LlmResponse response = llmProviderClient.callPrimaryModel(request.prompt());

        // True up actual usage against the budget (estimate vs actual can differ)
        quotaService.recordUsage(request.tenantId(), response.tokensUsed());

        return ResponseEntity.ok(response);
    }

    // Expose both algorithms directly too, so you can benchmark/compare them
    // independently in your load tests without going through the LLM mock.
    @PostMapping("/check/token-bucket")
    public RateLimitResult checkTokenBucket(@RequestParam String tenantId, @RequestParam(defaultValue = "1") long cost) {
        return tokenBucketLimiter.tryConsume(tenantId, cost);
    }

    @PostMapping("/check/sliding-window")
    public RateLimitResult checkSlidingWindow(@RequestParam String tenantId) {
        return slidingWindowLimiter.tryConsume(tenantId);
    }
}
