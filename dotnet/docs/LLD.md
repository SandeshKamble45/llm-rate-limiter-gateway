# LLM Gateway — .NET 10 low-level design

## 1. Request orchestration

GatewayService is the application use case. It validates the request, preserves the Java admission order and delegates all shared state transitions to IGatewayStateStore.

Dependency flow:

    GatewayController
         |
         v
    GatewayService
      |     |      |
      |     |      +-- IModelPricingService
      |     +--------- ITokenCounter
      +--------------- IGatewayStateStore
      +--------------- ILlmProvider
      +--------------- GatewayMetrics

## 2. Redis state store

RedisGatewayStateStore uses a singleton IConnectionMultiplexer and one IDatabase instance. Lua source files are embedded resources in the Infrastructure assembly and invoked with ScriptEvaluateAsync.

### Sliding-window script

Inputs:
- key: tenant-wide sliding-window key
- ARGV: window seconds, maximum requests, unique request ID

Atomic operations:
- Redis TIME
- ZREMRANGEBYSCORE
- ZCARD
- conditional ZADD
- ZRANGE WITHSCORES for retry delay
- EXPIRE

TTL is window seconds + 1.

### Token-bucket script

Inputs:
- key: tenant/model bucket
- ARGV: capacity, refill rate, requested input tokens

Atomic operations:
- Redis TIME
- HMGET tokens and last_refill
- calculate refill using elapsed seconds
- cap available tokens
- consume if sufficient, otherwise calculate retry-after
- HSET token balance and last refill
- EXPIRE 3600

### Status scripts

Status reads use Redis TIME as well. This removes the Java implementation's split-clock behaviour where mutation used Redis TIME but status calculations used the application machine clock.

### Budget reservation

Keys:
- KEYS[1] daily spend counter
- KEYS[2] reservation hash

Arguments:
- requested microdollars
- daily budget
- TTL seconds

The keys include the same Redis Cluster hash tag (tenant + UTC date). The script checks reservation status first, rejects an already settled reservation, and does not increment daily spend twice for a previously reserved ID.

### Budget settlement

The script:
- returns the stored result when the reservation is already settled,
- rejects unknown reservation IDs,
- calculates actual - reserved adjustment,
- clamps spend at zero,
- writes settlement details to the reservation hash,
- refreshes the daily and reservation TTL.

This improves on the Java settlement script, which accepted no reservation identifier and applied the adjustment each time it was called.

## 3. Tenant key strategy

Tenant IDs must match the application identifier policy: 1–128 characters; letters, digits, period, underscore and hyphen. The validated value is Base64URL-encoded before being used in Redis key names.

This prevents untrusted delimiter characters from changing key structure. It is not a replacement for authenticated tenant identity.

## 4. Pricing

ModelPricing stores integer microdollars per million tokens. Cost uses checked 64-bit integer multiplication and division. Positive token usage is rounded up to at least one microdollar to avoid free positive-usage calls due to truncation.

Configured pricing:
- primary-model: input 15,000; output 60,000 microdollars per million tokens.
- fallback-model: input 10,000; output 40,000.
- gpt-5-mini: input 250,000; output 2,000,000.

Prices are demo configuration values inherited from the Java source, not an assertion of live provider pricing. Keep prices in configuration and review against provider tariffs before any real billing use.

## 5. Token counting

Cl100kTokenCounter uses SharpToken with cl100k_base. This matches the encoding name selected by the Java jtokkit TokenCounter.

The unit test includes the known CL100K vector for “Hello, world!” (3 tokens). Before making a formal parity claim, run a larger golden-vector corpus from both Java and .NET and compare all counts.

## 6. Provider routing

ProviderRouter routes:
- primary-model to the deterministic mock provider when Gateway.Provider=mock.
- gpt-5-mini to the OpenAI provider if configured.
- OpenAI exceptions to fallback-model after the Polly pipeline fails.

OpenAiLlmProvider:
- calls POST /responses.
- sends model, input and max_output_tokens.
- reads output_text or nested output content.
- reads usage.input_tokens and usage.output_tokens.
- falls back to local CL100K counting if provider usage is missing/zero.

The original Java provider did not send max_output_tokens even though the budget reservation assumed 150 output tokens. The .NET implementation now sends the limit to align the execution cap with the reservation calculation.

## 7. Polly resilience

ProviderResiliencePipeline has:
- retry: one additional attempt, 200 ms initial delay, exponential backoff with jitter; only ProviderRateLimitedException (HTTP 429) is retryable.
- circuit breaker: failure ratio 0.40; minimum throughput 20; 30-second sampling duration; 10-second break duration.
- timeout: 40 seconds.

It intentionally does not retry an ambiguous timeout or generic 5xx response for an LLM POST, because the provider may have processed and billed the request already.

## 8. Budget lifecycle limitations

- Provider fallback uses fallback-model pricing during settlement.
- Cancellation keeps the reservation because the provider might have processed the request.
- An abnormal process termination after reservation can leave the reservation accounted in the daily counter until TTL expiry. A reconciliation worker/ledger is not yet implemented.
- The HTTP Idempotency-Key currently gives the reservation an identity; it is not a full HTTP response cache. Same-key concurrency and replay response semantics remain an outstanding task.

## 9. API DTOs

Domain records serialize with ASP.NET Core web defaults (camelCase). The contract fields mirror the Angular interfaces:
- ChatRequest: tenantId, prompt, model
- GatewayResponse: response, estimatedCostMicrodollars, actualCostMicrodollars, settlement, latencyMs
- GatewayStatusResponse: tenantId, tokenBucket, slidingWindow, dailyBudget, circuitBreaker

Keep serialization names stable until the Angular API client is deliberately versioned.
