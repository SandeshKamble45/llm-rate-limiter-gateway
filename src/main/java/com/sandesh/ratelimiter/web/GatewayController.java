package com.sandesh.ratelimiter.web;

import com.sandesh.ratelimiter.llm.LlmProviderClient;
import com.sandesh.ratelimiter.llm.TokenCounter;
import com.sandesh.ratelimiter.metrics.GatewayMetrics;
import com.sandesh.ratelimiter.model.CircuitBreakerStatus;
import com.sandesh.ratelimiter.model.RateLimitResult;
import com.sandesh.ratelimiter.model.SlidingWindowStatus;
import com.sandesh.ratelimiter.model.TokenBucketStatus;
import com.sandesh.ratelimiter.pricing.ModelPricing;
import com.sandesh.ratelimiter.pricing.ModelPricingService;
import com.sandesh.ratelimiter.quota.BudgetSettlementResult;
import com.sandesh.ratelimiter.quota.TenantQuotaService;
import com.sandesh.ratelimiter.ratelimit.SlidingWindowRateLimiter;
import com.sandesh.ratelimiter.ratelimit.TokenBucketRateLimiter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/gateway")
public class GatewayController {

        private static final int MAX_PROMPT_LENGTH = 10_000;
        private static final long MAX_OUTPUT_TOKENS = 150;

        private final TokenBucketRateLimiter tokenBucketLimiter;
        private final SlidingWindowRateLimiter slidingWindowLimiter;
        private final TenantQuotaService quotaService;
        private final LlmProviderClient llmProviderClient;
        private final TokenCounter tokenCounter;
        private final ModelPricingService modelPricingService;
        private final CircuitBreakerRegistry circuitBreakerRegistry;
        private final GatewayMetrics gatewayMetrics;

        public GatewayController(
                        TokenBucketRateLimiter tokenBucketLimiter,
                        SlidingWindowRateLimiter slidingWindowLimiter,
                        TenantQuotaService quotaService,
                        LlmProviderClient llmProviderClient,
                        TokenCounter tokenCounter,
                        ModelPricingService modelPricingService,
                        CircuitBreakerRegistry circuitBreakerRegistry,
                        GatewayMetrics gatewayMetrics) {

                this.tokenBucketLimiter = tokenBucketLimiter;
                this.slidingWindowLimiter = slidingWindowLimiter;
                this.quotaService = quotaService;
                this.llmProviderClient = llmProviderClient;
                this.tokenCounter = tokenCounter;
                this.modelPricingService = modelPricingService;
                this.circuitBreakerRegistry = circuitBreakerRegistry;
                this.gatewayMetrics = gatewayMetrics;
        }

        @GetMapping("/status")
        public ResponseEntity<?> getStatus(
                        @RequestParam String tenantId) {

                if (tenantId == null || tenantId.isBlank()) {
                        return ResponseEntity.badRequest()
                                        .body("tenantId is required");
                }

                String tenant = tenantId.trim();

                String tenantKey = tenant + ":primary-model";

                TokenBucketStatus tokenBucket = tokenBucketLimiter.getStatus(tenantKey);

                SlidingWindowStatus slidingWindow = slidingWindowLimiter.getStatus(tenant);

                TenantQuotaService.BudgetReservationResult budget = quotaService.getBudgetStatus(tenant);

                return ResponseEntity.ok(
                                new GatewayStatusResponse(
                                                tenant,
                                                tokenBucket,
                                                slidingWindow,
                                                budget,
                                                getCircuitBreakerStatus()));
        }

        @PostMapping("/chat")
        public ResponseEntity<?> chat(
                        @RequestBody ChatRequest request) {

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

                /*
                 * Count valid chat requests entering the rate-limit pipeline.
                 */
                gatewayMetrics.recordRequest();

                String tenantId = request.tenantId().trim();
                String prompt = request.prompt();

                String model = "primary-model";
                String tenantKey = tenantId + ":" + model;

                /*
                 * Step 1:
                 * Enforce request-per-minute limit.
                 */
                RateLimitResult requestRateResult = slidingWindowLimiter.tryConsume(tenantId);

                if (!requestRateResult.allowed()) {

                        gatewayMetrics.recordRejectedRequest();

                        return ResponseEntity.status(
                                        HttpStatus.TOO_MANY_REQUESTS)
                                        .body(requestRateResult);
                }

                /*
                 * Step 2:
                 * Estimate input tokens before calling the provider.
                 */
                long estimatedInputTokens = Math.max(
                                1,
                                tokenCounter.countTokens(prompt));

                /*
                 * Step 3:
                 * Enforce token-rate limit using the estimated
                 * input token consumption.
                 */
                RateLimitResult tokenRateResult = tokenBucketLimiter.tryConsume(
                                tenantKey,
                                estimatedInputTokens);

                if (!tokenRateResult.allowed()) {

                        gatewayMetrics.recordRejectedRequest();

                        return ResponseEntity.status(
                                        HttpStatus.TOO_MANY_REQUESTS)
                                        .body(tokenRateResult);
                }

                /*
                 * Step 4:
                 * Determine model pricing.
                 */
                ModelPricing pricing = modelPricingService.getPricing(model);

                /*
                 * Step 5:
                 * Reserve enough budget for the estimated input
                 * plus the maximum permitted output.
                 *
                 * This protects the daily quota before the external
                 * provider call happens.
                 */
                long estimatedReservation = pricing.calculateInputCost(
                                estimatedInputTokens)
                                + pricing.calculateOutputCost(
                                                MAX_OUTPUT_TOKENS);

                TenantQuotaService.BudgetReservationResult budgetReservation = quotaService.reserveBudget(
                                tenantId,
                                estimatedReservation);

                if (!budgetReservation.allowed()) {
                        return ResponseEntity.status(
                                        HttpStatus.PAYMENT_REQUIRED)
                                        .body(budgetReservation);
                }

                /*
                 * Step 6:
                 * Call the LLM provider.
                 *
                 * The latency timer covers the provider operation,
                 * including retry/fallback processing performed
                 * inside LlmProviderClient.
                 */
                gatewayMetrics.recordLlmCall();

                long llmStartNanos = System.nanoTime();

                LlmProviderClient.LlmResponse response;

                try {

                        response = llmProviderClient.callPrimaryModel(prompt);

                } catch (RuntimeException exception) {

                        gatewayMetrics.recordLlmFailure();

                        gatewayMetrics.recordLlmLatency(
                                        System.nanoTime() - llmStartNanos);

                        /*
                         * The budget was reserved before the provider call.
                         * Release that reservation because no provider usage
                         * occurred.
                         */
                        BudgetSettlementResult settlement = quotaService.settleBudget(
                                        tenantId,
                                        estimatedReservation,
                                        0);

                        return ResponseEntity.status(
                                        HttpStatus.BAD_GATEWAY)
                                        .body(
                                                        new GatewayErrorResponse(
                                                                        "LLM provider unavailable",
                                                                        estimatedReservation,
                                                                        settlement));
                }

                gatewayMetrics.recordLlmLatency(
                                System.nanoTime() - llmStartNanos);

                /*
                 * Step 7:
                 * Calculate actual provider usage and cost.
                 */
                ModelPricing actualPricing = modelPricingService.getPricing(
                                response.modelUsed());

                long actualInputCost = actualPricing.calculateInputCost(
                                response.usage().inputTokens());

                long actualOutputCost = actualPricing.calculateOutputCost(
                                response.usage().outputTokens());

                long actualCost = actualInputCost + actualOutputCost;

                /*
                 * Record actual token usage.
                 */
                gatewayMetrics.recordTokens(
                                response.usage().totalTokens());

                /*
                 * Record actual calculated cost.
                 */
                gatewayMetrics.recordActualCost(actualCost);

                /*
                 * Step 8:
                 * Settle the budget reservation using actual usage.
                 */
                BudgetSettlementResult settlement = quotaService.settleBudget(
                                tenantId,
                                estimatedReservation,
                                actualCost);

                return ResponseEntity.ok(
                                new GatewayResponse(
                                                response,
                                                estimatedReservation,
                                                actualCost,
                                                settlement));
        }

        @PostMapping("/check/token-bucket")
        public RateLimitResult checkTokenBucket(
                        @RequestParam String tenantId,
                        @RequestParam(defaultValue = "1") long cost) {

                return tokenBucketLimiter.tryConsume(
                                tenantId,
                                cost);
        }

        @PostMapping("/check/sliding-window")
        public RateLimitResult checkSlidingWindow(
                        @RequestParam String tenantId) {

                return slidingWindowLimiter.tryConsume(
                                tenantId);
        }

        private CircuitBreakerStatus getCircuitBreakerStatus() {

                CircuitBreaker circuitBreaker = circuitBreakerRegistry
                                .circuitBreaker("primaryLlm");

                CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

                return new CircuitBreakerStatus(
                                circuitBreaker.getName(),
                                circuitBreaker.getState().name(),
                                metrics.getFailureRate(),
                                metrics.getNumberOfBufferedCalls(),
                                metrics.getNumberOfFailedCalls());
        }

        public record ChatRequest(
                        String tenantId,
                        String prompt) {
        }

        public record GatewayResponse(
                        LlmProviderClient.LlmResponse response,
                        long estimatedCostMicrodollars,
                        long actualCostMicrodollars,
                        BudgetSettlementResult settlement) {
        }

        public record GatewayErrorResponse(
                        String message,
                        long releasedReservationMicrodollars,
                        BudgetSettlementResult settlement) {
        }

        public record GatewayStatusResponse(
                        String tenantId,
                        TokenBucketStatus tokenBucket,
                        SlidingWindowStatus slidingWindow,
                        TenantQuotaService.BudgetReservationResult dailyBudget,
                        CircuitBreakerStatus circuitBreaker) {
        }
}