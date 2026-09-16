package com.sandesh.ratelimiter.web;

import com.sandesh.ratelimiter.llm.LlmProviderClient;
import com.sandesh.ratelimiter.llm.TokenCounter;
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

    private static final int MAX_PROMPT_LENGTH = 10_000;

    private final TokenBucketRateLimiter tokenBucketLimiter;
    private final SlidingWindowRateLimiter slidingWindowLimiter;
    private final TenantQuotaService quotaService;
    private final LlmProviderClient llmProviderClient;
    private final TokenCounter tokenCounter;

    public GatewayController(
            TokenBucketRateLimiter tokenBucketLimiter,
            SlidingWindowRateLimiter slidingWindowLimiter,
            TenantQuotaService quotaService,
            LlmProviderClient llmProviderClient,
            TokenCounter tokenCounter) {
        this.tokenBucketLimiter = tokenBucketLimiter;
        this.slidingWindowLimiter = slidingWindowLimiter;
        this.quotaService = quotaService;
        this.llmProviderClient = llmProviderClient;
        this.tokenCounter = tokenCounter;
    }

    public record ChatRequest(String tenantId, String prompt) {}

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body("Request body is required");
        }

        if (request.tenantId() == null
                || request.tenantId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body("tenantId is required");
        }

        if (request.prompt() == null
                || request.prompt().isBlank()) {
            return ResponseEntity.badRequest()
                    .body("prompt is required");
        }

        if (request.prompt().length() > MAX_PROMPT_LENGTH) {
            return ResponseEntity.badRequest()
                    .body("prompt cannot exceed "
                            + MAX_PROMPT_LENGTH
                            + " characters");
        }

        String tenantId = request.tenantId().trim();
        String prompt = request.prompt();

        String tenantKey = tenantId + ":default-model";

        RateLimitResult requestRateResult =
                slidingWindowLimiter.tryConsume(tenantId);

        if (!requestRateResult.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(requestRateResult);
        }

        long estimatedTokens =
                Math.max(1, tokenCounter.countTokens(prompt));

        RateLimitResult tokenRateResult =
                tokenBucketLimiter.tryConsume(
                        tenantKey,
                        estimatedTokens);

        if (!tokenRateResult.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(tokenRateResult);
        }

        long estimatedCostMicrodollars =
                estimateCostMicrodollars(estimatedTokens);

        TenantQuotaService.BudgetReservationResult budgetResult =
                quotaService.reserveBudget(
                        tenantId,
                        estimatedCostMicrodollars);

        if (!budgetResult.allowed()) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(budgetResult);
        }

        LlmProviderClient.LlmResponse response =
                llmProviderClient.callPrimaryModel(prompt);

        return ResponseEntity.ok(response);
    }

    private long estimateCostMicrodollars(long tokens) {
        return Math.max(
                1,
                (tokens * 15_000L) / 1_000L);
    }

    @PostMapping("/check/token-bucket")
    public RateLimitResult checkTokenBucket(
            @RequestParam String tenantId,
            @RequestParam(defaultValue = "1") long cost) {
        return tokenBucketLimiter.tryConsume(tenantId, cost);
    }

    @PostMapping("/check/sliding-window")
    public RateLimitResult checkSlidingWindow(
            @RequestParam String tenantId) {
        return slidingWindowLimiter.tryConsume(tenantId);
    }
}