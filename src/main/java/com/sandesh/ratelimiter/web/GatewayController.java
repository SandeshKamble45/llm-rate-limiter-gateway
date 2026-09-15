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

    public GatewayController(
            TokenBucketRateLimiter tokenBucketLimiter,
            SlidingWindowRateLimiter slidingWindowLimiter,
            TenantQuotaService quotaService,
            LlmProviderClient llmProviderClient) {
        this.tokenBucketLimiter = tokenBucketLimiter;
        this.slidingWindowLimiter = slidingWindowLimiter;
        this.quotaService = quotaService;
        this.llmProviderClient = llmProviderClient;
    }

    public record ChatRequest(String tenantId, String prompt) {
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {

        String tenantId = request.tenantId();
        String tenantKey = tenantId + ":default-model";

        /*
         * Layer 1: request-rate protection.
         *
         * Sliding window answers:
         * "Has this tenant sent too many requests in the current
         * rolling time window?"
         */
        RateLimitResult requestRateResult =
                slidingWindowLimiter.tryConsume(tenantId);

        if (!requestRateResult.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(requestRateResult);
        }

        /*
         * Layer 2: token-based protection.
         *
         * Estimate the request's token cost before calling the provider.
         * This is intentionally a rough estimate for the current mock
         * implementation. Real tokenizer integration comes later.
         */
        long estimatedTokens =
                Math.max(1, request.prompt().length() / 4);

        RateLimitResult tokenRateResult =
                tokenBucketLimiter.tryConsume(
                        tenantKey,
                        estimatedTokens
                );

        if (!tokenRateResult.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(tokenRateResult);
        }

        /*
         * Layer 3: daily financial budget.
         *
         * Reserve the estimated cost atomically before making the
         * external LLM call.
         *
         * Current scaffold:
         * 1 token = 1.5 cents / 1000 tokens.
         *
         * This pricing is temporary and will be replaced by
         * model-specific pricing later.
         */
        long estimatedCostMicrodollars =
                estimateCostMicrodollars(estimatedTokens);

        TenantQuotaService.BudgetReservationResult budgetResult =
                quotaService.reserveBudget(
                        tenantId,
                        estimatedCostMicrodollars
                );

        if (!budgetResult.allowed()) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(budgetResult);
        }

        /*
         * Layer 4: call the LLM provider.
         *
         * Resilience4j circuit breaker + retry are currently handled
         * inside LlmProviderClient.
         */
        LlmProviderClient.LlmResponse response =
                llmProviderClient.callPrimaryModel(
                        request.prompt()
                );

        /*
         * Actual usage settlement will be implemented after we introduce
         * provider-specific token usage and model pricing.
         */
        return ResponseEntity.ok(response);
    }

    /*
     * Temporary cost estimation for the mock implementation.
     *
     * Current scaffold pricing:
     *
     * 1.5 USD cents / 1000 tokens
     *
     * Convert that to microdollars:
     *
     * 1 USD = 1,000,000 microdollars
     * 1 USD cent = 10,000 microdollars
     * 1.5 cents = 15,000 microdollars
     *
     * Therefore:
     *
     * cost = tokens * 15,000 / 1000
     */
    private long estimateCostMicrodollars(long tokens) {
        return Math.max(
                1,
                (tokens * 15_000L) / 1_000L
        );
    }

    /*
     * Expose the token bucket independently so that we can benchmark
     * the algorithm without invoking the LLM provider.
     */
    @PostMapping("/check/token-bucket")
    public RateLimitResult checkTokenBucket(
            @RequestParam String tenantId,
            @RequestParam(defaultValue = "1") long cost) {

        return tokenBucketLimiter.tryConsume(
                tenantId,
                cost
        );
    }

    /*
     * Expose the sliding window independently so that we can benchmark
     * and compare both algorithms.
     */
    @PostMapping("/check/sliding-window")
    public RateLimitResult checkSlidingWindow(
            @RequestParam String tenantId) {

        return slidingWindowLimiter.tryConsume(tenantId);
    }
}