# LLM Rate Limiter Gateway — Low-Level Design

## 1. Purpose

This document describes the implementation-level design of the LLM Rate Limiter Gateway.

The HLD explains the system-level design and trade-offs. This LLD maps those decisions to the actual Spring Boot implementation:

- Java packages and classes
- Redis keys and data structures
- Redis Lua scripts
- request-processing order
- provider abstraction and resilience
- pricing and budget accounting
- DTOs/domain records
- concurrency and atomicity
- configuration
- integration and unit tests

The implementation uses Spring Boot, Spring Data Redis, Redis Lua scripting, Resilience4j, Micrometer/Actuator, and Testcontainers.

---

## 2. Package Structure

```text
src/main/java/com/sandesh/ratelimiter/
│
├── RateLimiterGatewayApplication.java
│
├── config/
│   └── RedisConfig.java
│
├── web/
│   └── GatewayController.java
│
├── ratelimit/
│   ├── SlidingWindowRateLimiter.java
│   └── TokenBucketRateLimiter.java
│
├── quota/
│   ├── TenantQuotaService.java
│   └── BudgetSettlementResult.java
│
├── pricing/
│   ├── ModelPricing.java
│   └── ModelPricingService.java
│
├── llm/
│   ├── LlmProvider.java
│   ├── LlmProviderClient.java
│   ├── LlmProviderSelectionProperties.java
│   ├── MockLlmProvider.java
│   ├── FallbackLlmProvider.java
│   ├── OpenAiLlmProvider.java
│   ├── OpenAiProperties.java
│   └── TokenCounter.java
│
├── metrics/
│   └── GatewayMetrics.java
│
└── model/
    ├── CircuitBreakerStatus.java
    ├── RateLimitResult.java
    ├── SlidingWindowStatus.java
    ├── TokenBucketStatus.java
    └── UsageMetadata.java
```

Redis Lua scripts are stored under:

```text
src/main/resources/scripts/
├── sliding_window.lua
├── token_bucket.lua
├── budget_reservation.lua
└── budget_settlement.lua
```

---

## 3. Component Responsibilities

| Component | Responsibility |
|---|---|
| `GatewayController` | HTTP entry point, validation, orchestration, response construction |
| `SlidingWindowRateLimiter` | Request-frequency admission using Redis sorted set |
| `TokenBucketRateLimiter` | Token-capacity admission using Redis hash + Lua |
| `TenantQuotaService` | Daily monetary budget reservation and settlement |
| `ModelPricingService` | Maps model names to input/output pricing |
| `ModelPricing` | Calculates token-based microdollar cost |
| `TokenCounter` | Estimates tokens using `CL100K_BASE` encoding |
| `LlmProviderClient` | Selects provider/model and applies retry + circuit breaker |
| `LlmProvider` | Provider abstraction |
| `MockLlmProvider` | Local/demo primary provider implementation |
| `OpenAiLlmProvider` | Live OpenAI provider implementation |
| `FallbackLlmProvider` | Secondary fallback model implementation |
| `GatewayMetrics` | Micrometer counters and LLM latency timer |
| `RedisConfig` | RedisTemplate and Lua-script bean configuration |

---

## 4. HTTP API Layer

### 4.1 Base Path

```text
/v1/gateway
```

### 4.2 `POST /v1/gateway/chat`

Request body:

```json
{
  "tenantId": "tenant-1",
  "prompt": "Explain Redis Lua atomicity",
  "model": "primary-model"
}
```

`model` is optional. When absent, the controller uses the primary model returned by `LlmProviderClient`.

The controller also accepts the optional header:

```text
X-Demo-Access-Code
```

A non-primary model requires the configured demo access code.

### 4.3 `GET /v1/gateway/status`

```text
GET /v1/gateway/status?tenantId=tenant-1
```

Returns current token-bucket status, sliding-window status, daily budget status, and circuit-breaker status.

### 4.4 Diagnostic rate-limit endpoints

```text
POST /v1/gateway/check/token-bucket
POST /v1/gateway/check/sliding-window
```

These directly exercise the two rate-limiting services.

---

## 5. `GatewayController`

`GatewayController` is the orchestration layer. It does not implement Redis algorithms itself; instead, it invokes the specialized services in sequence.

### Main dependencies

```java
TokenBucketRateLimiter
SlidingWindowRateLimiter
TenantQuotaService
LlmProviderClient
TokenCounter
ModelPricingService
CircuitBreakerRegistry
GatewayMetrics
```

### Request processing order

```text
HTTP request
    │
    ▼
Validate body / tenant / prompt
    │
    ▼
Resolve model
    │
    ▼
Sliding-window admission
    │
    ├── rejected → 429
    │
    ▼
Estimate input tokens
    │
    ▼
Token-bucket admission
    │
    ├── rejected → 429
    │
    ▼
Resolve model pricing
    │
    ▼
Reserve maximum estimated budget
    │
    ├── rejected → 402
    │
    ▼
Call LlmProviderClient
    │
    ├── failure → release reservation → 502
    │
    ▼
Read actual provider usage
    │
    ▼
Calculate actual cost
    │
    ▼
Settle budget reservation
    │
    ▼
Return GatewayResponse
```

### Input validation

The controller rejects:

- missing request body
- missing/blank `tenantId`
- missing/blank `prompt`
- prompts longer than 10,000 characters
- protected model requests without a valid demo access code

The request counter is incremented only after these initial validations pass.

---

## 6. Request Admission: Sliding Window

Class:

```text
SlidingWindowRateLimiter
```

### Redis key

```text
ratelimit:sw:<tenantKey>
```

The controller currently passes `tenantId` to this limiter, so the request-frequency window is **tenant-wide**, rather than model-specific.

Example:

```text
ratelimit:sw:tenant-1
```

### Redis data structure

A Redis **sorted set** is used.

Each request is stored as:

```text
score  = request timestamp in milliseconds
member = UUID request ID
```

### Admission algorithm

The Lua script performs the entire operation atomically:

```text
Redis TIME
    ↓
Calculate current timestamp
    ↓
Remove entries outside the window
    ↓
ZCARD active requests
    ↓
Compare count with maxRequests
    ├── capacity available
    │      ↓
    │   ZADD request
    │      ↓
    │   allowed = 1
    │
    └── window full
           ↓
       find oldest request
           ↓
       calculate retry-after
```

### Current configuration

```yaml
ratelimit:
  sliding-window:
    window-seconds: 60
    max-requests: 100
```

### Retry calculation

When the window is full, the oldest request determines when capacity can become available:

```text
retryAfter ≈ oldestTimestamp + windowSize - currentTime
```

The script returns at least one second when the window is full.

### Key expiration

The script applies:

```text
EXPIRE key windowSeconds + 1
```

This prevents inactive tenant keys from remaining indefinitely.

---

## 7. Token Bucket

Class:

```text
TokenBucketRateLimiter
```

### Redis key

```text
ratelimit:tb:<tenantId>:<model>
```

Example:

```text
ratelimit:tb:tenant-1:primary-model
```

Unlike the sliding-window limiter, the token bucket is currently scoped by both tenant and selected model.

### Redis data structure

A Redis hash stores:

```text
tokens
last_refill
```

Conceptually:

```text
ratelimit:tb:tenant-1:primary-model
    tokens      → 9842.5
    last_refill → 1757570000.42
```

### Current configuration

```yaml
ratelimit:
  token-bucket:
    capacity: 10000
    refill-rate-per-sec: 50
```

### Atomic state transition

The Lua script performs:

```text
Read tokens + last_refill
        ↓
Get Redis TIME
        ↓
Calculate elapsed time
        ↓
Refill tokens
        ↓
Cap at bucket capacity
        ↓
Compare with requested tokens
        ├── enough
        │     ↓
        │   subtract requested tokens
        │     ↓
        │   allowed
        │
        └── insufficient
              ↓
          calculate retry-after
        ↓
Persist tokens + last_refill
```

### Refill equation

Conceptually:

```text
elapsed = now - last_refill

refilled = min(
    capacity,
    tokens + elapsed × refillRate
)
```

If enough tokens exist:

```text
remaining = refilled - requested
```

If not enough exist:

```text
missing = requested - refilled
retryAfter = ceil(missing / refillRate)
```

### Validation

`TokenBucketRateLimiter` rejects invalid requests when:

```text
requestedCost <= 0
```

or:

```text
requestedCost > capacity
```

with `IllegalArgumentException`.

### Important implementation detail

The controller currently passes the **estimated input-token count** to the token bucket. The current implementation does not perform a second token-bucket settlement using actual completion tokens after the provider returns.

Therefore, the token bucket should be described as **estimated input-token admission control in the current implementation**, even though the broader project design is token-aware.

---

## 8. Token Estimation

Class:

```text
TokenCounter
```

The implementation uses:

```text
jtokkit
CL100K_BASE
```

The controller performs:

```java
long estimatedInputTokens = Math.max(
    1,
    tokenCounter.countTokens(prompt));
```

This estimate is used for token-bucket admission and input-cost estimation.

The live OpenAI provider uses provider-reported usage when available and falls back to the local tokenizer if usage fields are missing.

---

## 9. Model Pricing

### `ModelPricingService`

Pricing is held in an in-memory map.

Current models:

| Model | Input microdollars / 1M tokens | Output microdollars / 1M tokens |
|---|---:|---:|
| `primary-model` | 15,000 | 60,000 |
| `fallback-model` | 10,000 | 40,000 |
| `gpt-5-mini` | 250,000 | 2,000,000 |

An unsupported model causes:

```text
IllegalArgumentException
```

### `ModelPricing`

The cost calculation is integer-based:

```text
cost = tokens × pricePerMillion / 1,000,000
```

For positive token counts, the implementation applies a minimum calculated cost of one microdollar.

This avoids floating-point arithmetic for monetary accounting.

---

## 10. Tenant Budget Service

Class:

```text
TenantQuotaService
```

The service performs two separate state transitions:

1. budget reservation
2. budget settlement

### Monetary unit

Budget values are stored as integer microdollars.

```text
$1 = 1,000,000 microdollars
$5 = 5,000,000 microdollars
```

Default daily budget:

```text
5,000,000 microdollars
```

### Daily Redis key

```text
quota:daily:<tenantId>:<UTC-date>
```

Example:

```text
quota:daily:tenant-1:2026-10-02
```

The date is generated using UTC:

```java
LocalDate.now(ZoneOffset.UTC)
```

### 10.1 Budget reservation

The controller reserves:

```text
estimated input cost
+
maximum output cost
```

where the maximum output is currently:

```text
150 tokens
```

The Lua reservation script performs:

```text
GET current spend
        ↓
current + requested > budget ?
        ├── yes → reject
        └── no
             ↓
          INCRBY requested
             ↓
          EXPIRE key
             ↓
          allow
```

This makes the check-and-reserve operation atomic.

### Reservation result

```java
BudgetReservationResult(
    allowed,
    spentMicrodollars,
    remainingMicrodollars
)
```

### 10.2 Settlement

After the provider call, the controller calculates actual cost and calls:

```java
quotaService.settleBudget(
    tenantId,
    estimatedReservation,
    actualCost
);
```

The Lua script calculates:

```text
adjustment = actual - reserved
newSpent = currentSpent + adjustment
```

Therefore:

```text
actual < reserved
    → negative adjustment
    → reservation is released

actual > reserved
    → positive adjustment
    → additional spend is recorded
```

The script also prevents the resulting accounting value from becoming negative.

### Settlement result

```java
BudgetSettlementResult(
    reservedMicrodollars,
    actualMicrodollars,
    adjustmentMicrodollars,
    spentMicrodollars,
    remainingMicrodollars,
    withinBudget
)
```

### Provider failure settlement

If the provider operation throws a `RuntimeException`, the controller settles:

```text
reserved = estimatedReservation
actual   = 0
```

This releases the reservation because no provider usage occurred.

The endpoint then returns:

```text
HTTP 502 Bad Gateway
```

---

## 11. Provider Abstraction

### `LlmProvider`

The provider interface exposes:

```java
String getModel();
LlmResponse complete(String prompt);
```

`LlmResponse` contains:

```java
String modelUsed
String completion
UsageMetadata usage
```

`UsageMetadata` contains:

```text
inputTokens
outputTokens
totalTokens
```

### Provider implementations

```text
LlmProvider
    │
    ├── MockLlmProvider
    ├── OpenAiLlmProvider
    └── FallbackLlmProvider
```

---

## 12. `LlmProviderClient`

`LlmProviderClient` is the provider-routing and resilience boundary.

At construction time it selects the primary provider based on:

```yaml
llm:
  provider: ${LLM_PROVIDER:mock}
```

The primary provider is therefore either:

```text
mock
```

or:

```text
openai
```

### Explicit model routing

`callModel(model, prompt)` currently supports:

```text
primary-model → MockLlmProvider
configured OpenAI model → OpenAiLlmProvider
```

An unsupported model throws `IllegalArgumentException`.

### Resilience annotations

The provider call is annotated with:

```java
@CircuitBreaker(
    name = "primaryLlm",
    fallbackMethod = "fallbackToSelectedModel")
@Retry(name = "primaryLlm")
```

The same resilience configuration is also present on `callPrimaryModel`.

### Fallback

When the protected call reaches the fallback path:

```text
LlmProviderClient
      ↓
FallbackLlmProvider
      ↓
fallback-model
```

The fallback provider generates a local fallback completion and calculates usage using `TokenCounter`.

---

## 13. Resilience4j Configuration

Current configuration:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      primaryLlm:
        sliding-window-size: 20
        failure-rate-threshold: 40
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 5
        automatic-transition-from-open-to-half-open-enabled: true

  retry:
    instances:
      primaryLlm:
        max-attempts: 2
        wait-duration: 200ms
```

### Circuit breaker state model

```text
CLOSED
  │
  │ failure rate threshold reached
  ▼
OPEN
  │
  │ after 10 seconds
  ▼
HALF_OPEN
  │
  ├── successful test calls → CLOSED
  │
  └── failures → OPEN
```

The controller reads circuit-breaker state through `CircuitBreakerRegistry` for the status endpoint.

---

## 14. Live OpenAI Provider

Class:

```text
OpenAiLlmProvider
```

It uses Spring's `RestClient`.

Configuration:

```yaml
llm:
  openai:
    api-key: ${OPENAI_API_KEY:}
    base-url: ${OPENAI_BASE_URL:https://api.openai.com/v1}
    model: ${OPENAI_MODEL:gpt-5-mini}
```

The provider sends a POST request to:

```text
/responses
```

with the configured model and prompt input.

The API key is read from configuration and supplied as a bearer token.

### Usage extraction

The provider reads:

```text
usage.input_tokens
usage.output_tokens
```

When usage is missing, `TokenCounter` provides a defensive local estimate.

---

## 15. Mock Provider

`MockLlmProvider` is the default local/demo provider.

It uses:

```text
model = primary-model
```

It intentionally simulates occasional primary-provider failures using a 15% random failure condition. This allows retry/circuit/fallback behavior to be exercised without depending on an external provider.

The fallback provider always returns:

```text
model = fallback-model
```

---

## 16. Redis Configuration

`RedisConfig` creates:

```text
RedisTemplate<String, Object>
```

with `StringRedisSerializer` for keys and values/hash fields.

It also exposes four `DefaultRedisScript<List>` beans:

```text
tokenBucketScript
slidingWindowScript
budgetReservationScript
budgetSettlementScript
```

Each script is loaded from the classpath under:

```text
src/main/resources/scripts/
```

The rate-limit and budget services execute these scripts through:

```java
redisTemplate.execute(...)
```

This keeps the critical read/compute/write transition inside Redis.

---

## 17. Redis Data Model

| Purpose | Key pattern | Redis structure | Main fields |
|---|---|---|---|
| Sliding window | `ratelimit:sw:<tenant>` | Sorted Set | request UUID → timestamp score |
| Token bucket | `ratelimit:tb:<tenant>:<model>` | Hash | `tokens`, `last_refill` |
| Daily budget | `quota:daily:<tenant>:<UTC-date>` | String/integer value | accumulated microdollars |

### State ownership

Redis is the shared state layer. The application instances do not keep the authoritative rate-limit or budget state in JVM memory.

This allows multiple gateway instances to coordinate against the same tenant state.

---

## 18. Lua Scripts and Atomicity

The gateway has four Lua scripts because there are four independent atomic state transitions.

### `sliding_window.lua`

Purpose:

```text
expire old requests
→ count active requests
→ admit/reject
→ add request
→ calculate retry time
```

### `token_bucket.lua`

Purpose:

```text
read bucket
→ refill
→ check requested tokens
→ consume if allowed
→ persist state
```

### `budget_reservation.lua`

Purpose:

```text
read spend
→ check budget
→ increment spend if allowed
```

### `budget_settlement.lua`

Purpose:

```text
read current spend
→ apply actual-reservation adjustment
→ clamp at zero
→ persist spend
→ return accounting result
```

### Why Lua instead of multiple Redis commands?

A sequence such as:

```text
GET
calculate
SET
```

would expose a race between gateway instances.

Lua executes the state transition as a single Redis-side operation, so another Redis command cannot interleave with the script's internal sequence.

---

## 19. Concurrency Model

The critical correctness requirement is that two gateway instances cannot independently approve conflicting state transitions.

### Example: budget race

Without atomic reservation:

```text
Budget = $1.00

Instance A reads $1.00
Instance B reads $1.00

A reserves $0.70
B reserves $0.70
```

Both could incorrectly succeed.

With the Lua reservation script:

```text
Instance A ─┐
            ├── Redis atomic script ──> one serialized state transition
Instance B ─┘
```

The second operation sees the updated value and is rejected when the budget is insufficient.

The same principle applies to the sliding-window and token-bucket state transitions.

---

## 20. Status Reads vs Admission Writes

There is a deliberate distinction between mutation paths and dashboard/status reads.

### Admission path

Uses Lua and Redis's authoritative clock where the Lua script requires time.

### Status path

Uses ordinary Redis reads and computes an approximate/current display value in Java.

For example, `TokenBucketRateLimiter.getStatus()` reads `tokens` and `last_refill` and estimates the currently available tokens using JVM system time.

Therefore, status endpoints are observational and are not the synchronization mechanism for admission decisions.

---

## 21. Observability

`GatewayMetrics` uses Micrometer counters and timers.

Current metrics include:

```text
gateway.requests
gateway.requests.rejected
gateway.llm.calls
gateway.llm.failures
gateway.tokens
gateway.actual.cost.microdollars
gateway.llm.latency
```

The application also exposes Spring Boot Actuator endpoints for:

```text
health
metrics
prometheus
circuitbreakers
```

The controller records:

- valid gateway requests
- rejected requests
- LLM calls
- LLM failures
- actual tokens
- actual cost
- provider-operation latency

---

## 22. Error and HTTP Response Mapping

The implemented controller explicitly returns these outcomes:

| Condition | HTTP status |
|---|---:|
| Missing request body | 400 |
| Missing tenant ID | 400 |
| Missing prompt | 400 |
| Prompt too long | 400 |
| Invalid/missing protected-model access code | 403 |
| Sliding-window rejection | 429 |
| Token-bucket rejection | 429 |
| Daily budget rejection | 402 |
| Provider runtime failure | 502 |

Some lower-level invalid arguments, such as unsupported models or token costs greater than bucket capacity, are thrown as `IllegalArgumentException` from the service layer rather than explicitly mapped by `GatewayController`.

---

## 23. Response Models

### `RateLimitResult`

```java
record RateLimitResult(
    boolean allowed,
    double remainingTokens,
    String algorithm,
    long retryAfterSeconds
)
```

The field name `remainingTokens` is reused for both rate-limit algorithms even though the sliding-window value represents remaining requests.

### `GatewayResponse`

```java
record GatewayResponse(
    LlmProvider.LlmResponse response,
    long estimatedCostMicrodollars,
    long actualCostMicrodollars,
    BudgetSettlementResult settlement,
    long latencyMs
)
```

### `GatewayErrorResponse`

```java
record GatewayErrorResponse(
    String message,
    long releasedReservationMicrodollars,
    BudgetSettlementResult settlement
)
```

### `GatewayStatusResponse`

```java
record GatewayStatusResponse(
    String tenantId,
    TokenBucketStatus tokenBucket,
    SlidingWindowStatus slidingWindow,
    BudgetReservationResult dailyBudget,
    CircuitBreakerStatus circuitBreaker
)
```

These request/response records are nested directly in `GatewayController`; there are no separate controller DTO classes in the current implementation.

---

## 24. Class Relationship View

```text
                         ┌────────────────────────┐
                         │   GatewayController    │
                         └───────────┬────────────┘
                                     │
             ┌───────────────────────┼────────────────────────┐
             │                       │                        │
             ▼                       ▼                        ▼
   SlidingWindowRateLimiter   TokenBucketRateLimiter   TenantQuotaService
             │                       │                        │
             └───────────────┬───────┴────────────────────────┘
                             │
                             ▼
                      RedisTemplate
                             │
                  ┌──────────┴──────────┐
                  ▼                     ▼
             Redis Lua scripts       Redis state

GatewayController
        │
        ├── TokenCounter
        ├── ModelPricingService
        ├── GatewayMetrics
        │
        └── LlmProviderClient
                    │
                    ├── MockLlmProvider
                    ├── OpenAiLlmProvider
                    └── FallbackLlmProvider
```

---

## 25. End-to-End Sequence

```text
Client
  │
  │ POST /v1/gateway/chat
  ▼
GatewayController
  │
  ├── validate request
  │
  ├── resolve model
  │
  ├── SlidingWindowRateLimiter
  │       │
  │       └── Redis Lua
  │
  ├── TokenCounter
  │
  ├── TokenBucketRateLimiter
  │       │
  │       └── Redis Lua
  │
  ├── ModelPricingService
  │
  ├── TenantQuotaService.reserveBudget()
  │       │
  │       └── Redis Lua
  │
  ├── LlmProviderClient.callModel()
  │       │
  │       ├── Retry
  │       ├── Circuit Breaker
  │       └── FallbackLlmProvider
  │
  ├── calculate actual cost
  │
  ├── TenantQuotaService.settleBudget()
  │       │
  │       └── Redis Lua
  │
  └── GatewayResponse
  │
  ▼
Client
```

---

## 26. Testing Strategy

The project uses both unit tests and Redis-backed integration tests.

### Unit tests

```text
LlmProviderClientTest
TokenCounterTest
GatewayMetricsTest
ModelPricingTest
```

These test isolated behavior such as provider responses, token counting, metrics, and pricing calculations.

### Redis integration tests

The rate limiter and quota services are tested against real Redis containers using Testcontainers.

```text
SlidingWindowRateLimiterIntegrationTest
TokenBucketRateLimiterIntegrationTest
TenantQuotaServiceIntegrationTest
TenantQuotaSettlementIntegrationTest
```

This is important because the critical behavior depends on actual Redis Lua execution and Redis data structures.

### Controller integration test

```text
GatewayControllerIntegrationTest
```

The controller is tested with Spring Boot + MockMvc + Testcontainers Redis, including:

- successful chat requests
- validation failures
- sliding-window rejection
- token counting/rate limiting
- budget behavior
- provider failure handling
- response structure

### Test Redis configuration

Integration tests override the Redis host/port dynamically using the Testcontainers Redis container.

This avoids depending on a developer's locally installed Redis instance.

---

## 27. Configuration Reference

### Redis

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      ssl:
        enabled: ${REDIS_SSL_ENABLED:false}
```

### Token bucket

```yaml
ratelimit:
  token-bucket:
    capacity: 10000
    refill-rate-per-sec: 50
```

### Sliding window

```yaml
ratelimit:
  sliding-window:
    window-seconds: 60
    max-requests: 100
```

### Circuit breaker

```yaml
sliding-window-size: 20
failure-rate-threshold: 40
wait-duration-in-open-state: 10s
permitted-number-of-calls-in-half-open-state: 5
automatic-transition-from-open-to-half-open-enabled: true
```

### Retry

```yaml
max-attempts: 2
wait-duration: 200ms
```

### Daily quota

The service currently uses:

```text
quota.daily-budget-microdollars
```

with a default of:

```text
5,000,000 microdollars = $5
```

### Gateway constants

The controller currently defines:

```text
MAX_PROMPT_LENGTH = 10,000 characters
MAX_OUTPUT_TOKENS = 150 tokens
```

---

## 28. Important Implementation Boundaries

The following distinctions should be preserved when explaining the current implementation.

### 28.1 Request-rate state is tenant-wide

The controller calls:

```java
slidingWindowLimiter.tryConsume(tenantId)
```

so the sliding-window request count is currently shared across models for a tenant.

### 28.2 Token-bucket state is tenant + model

The controller constructs:

```java
String tenantKey = tenantId + ":" + model;
```

so token-bucket capacity is isolated by tenant/model.

### 28.3 Budget state is tenant-wide

The daily quota key contains:

```text
tenantId + UTC date
```

and does not contain the model name.

Therefore, model costs contribute to the same tenant-level daily budget.

### 28.4 Status endpoint currently reports the primary-model token bucket

`GET /status` constructs:

```text
<tenant>:primary-model
```

for the token-bucket status.

It does not accept a model parameter for this status view.

### 28.5 Token-bucket admission currently uses estimated input tokens

The current controller consumes estimated prompt tokens before the provider call. Actual provider output tokens are used for cost accounting, but there is no post-response token-bucket adjustment in the current code.

### 28.6 Budget is explicitly reserved before provider execution

This is a core correctness property:

```text
reserve
  ↓
provider call
  ↓
actual cost
  ↓
settlement
```

### 28.7 Fallback cost uses the fallback model's pricing

After a fallback response, the controller uses:

```java
modelPricingService.getPricing(response.modelUsed())
```

Therefore, if the fallback model actually produces the response, its pricing is used for actual-cost calculation.

---

## 29. Design Rationale at Code Level

### Why separate rate limiter classes?

The two algorithms have different state models and semantics:

```text
SlidingWindowRateLimiter
→ request frequency
→ sorted set

TokenBucketRateLimiter
→ token capacity
→ hash
```

Separating them keeps each algorithm independently testable and avoids coupling the Redis implementation details.

### Why separate quota service?

Monetary accounting has different invariants from request/token limiting:

```text
reservation
settlement
microdollar precision
UTC daily boundary
```

Keeping it in `TenantQuotaService` makes the budget invariant explicit.

### Why provider interface?

`LlmProvider` isolates gateway logic from provider-specific communication.

The controller does not need to know whether the response came from:

```text
MockLlmProvider
OpenAiLlmProvider
FallbackLlmProvider
```

### Why calculate actual cost after the provider call?

The provider response contains actual usage metadata. The gateway therefore calculates the final cost from:

```text
actual input tokens
actual output tokens
actual model used
```

rather than assuming the initial reservation is the final charge.

---

## 30. Current Implementation Limitations

These are implementation-level limitations, not hidden assumptions:

1. Model pricing is stored in an in-memory map rather than an external configuration store.
2. Tenant daily budget is configured globally rather than persisted as a per-tenant policy.
3. Sliding-window request state is tenant-wide while token-bucket state is tenant/model scoped.
4. The status endpoint exposes the primary-model token bucket rather than an arbitrary selected model.
5. Token-bucket admission currently consumes estimated input tokens only; there is no post-response actual-token reconciliation.
6. Unsupported model/argument exceptions are not given dedicated controller-level HTTP mappings.
7. Redis is a central dependency for distributed rate-limit and budget correctness.
8. The exact sliding-window log stores one sorted-set member per admitted request, so memory usage grows with request volume inside the active window.

These boundaries should be reflected accurately in interview discussions rather than presenting future improvements as existing behavior.

---

## 31. Interview Drill-Down Path

A useful implementation-level explanation is:

```text
GatewayController
    ↓
Why two rate limiters?
    ↓
SlidingWindowRateLimiter
    ↓
Redis Sorted Set + Lua
    ↓
TokenBucketRateLimiter
    ↓
Redis Hash + Lua
    ↓
TenantQuotaService
    ↓
Reservation + Settlement Lua scripts
    ↓
LlmProviderClient
    ↓
Retry + Circuit Breaker + Fallback
    ↓
Actual usage + ModelPricing
    ↓
Budget settlement
    ↓
Response + Micrometer metrics
```

The strongest implementation-level point is that **the correctness-sensitive state transitions are executed atomically inside Redis rather than being implemented as separate application-side read/modify/write sequences.**

---

## 32. Summary

The implementation is organized around a thin HTTP orchestration layer and specialized services:

```text
GatewayController
    │
    ├── Admission control
    │     ├── Sliding window
    │     └── Token bucket
    │
    ├── Cost control
    │     ├── Model pricing
    │     └── Tenant budget
    │
    ├── Provider execution
    │     ├── Retry
    │     ├── Circuit breaker
    │     └── Fallback
    │
    ├── Usage settlement
    │
    └── Observability
```

Redis provides the shared distributed state, while Lua provides atomicity for the critical state transitions. Spring Boot provides the HTTP/service runtime, Resilience4j protects provider calls, and Micrometer/Actuator exposes operational telemetry.

The implementation therefore follows the core gateway sequence:

```text
Validate
  ↓
Limit request frequency
  ↓
Limit token capacity
  ↓
Estimate cost
  ↓
Reserve budget
  ↓
Call provider with resilience
  ↓
Read actual usage
  ↓
Calculate actual cost
  ↓
Settle budget
  ↓
Return response + metrics
```
