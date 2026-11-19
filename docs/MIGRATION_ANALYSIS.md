# Migration Analysis: Java Spring Boot to .NET 10

> Source revision inspected: `main` at commit `1bc5b0440d275a466a8a377f35b09f39bdf4dabb`.
> This document records observed behaviour and separates it from proposed .NET changes.

## Repository inventory

The repository is a Maven/Spring Boot 3.3.2 application targeting Java 17, with an Angular standalone frontend, Redis Lua scripts, Docker Compose, Prometheus configuration and architecture documents.

Backend modules: `web/GatewayController`, `config/RedisConfig`, `llm/` (provider abstraction, OpenAI Responses API, mock and fallback providers, provider selection, token counter), `metrics/GatewayMetrics`, `model/` DTO records, `pricing/`, `quota/TenantQuotaService`, and `ratelimit/` sliding-window and token-bucket services. Four Lua scripts are under `src/main/resources/scripts`.

Frontend API wrapper: `frontend/src/app/core/services/gateway-api.ts`; models: `frontend/src/app/core/models/gateway.models.ts`. Dashboard calls `/api/v1/gateway`; backend controller route prefix is `/v1/gateway`, implying a proxy or deployment `/api` prefix. Health uses `/actuator/health`.

Infrastructure: Maven and Spring Boot; Redis 7 Alpine in Docker Compose; Prometheus config and Actuator/Micrometer; Testcontainers Redis integration suites; Java 17 Docker multi-stage build.

## Existing API contract

### POST `/v1/gateway/chat`
Request JSON: `{ tenantId, prompt, model? }`. Optional header: `X-Demo-Access-Code`.

Validation and outcomes observed:
- Body required; tenantId required/nonblank and trimmed.
- Prompt required/nonblank, maximum 10,000 characters.
- Missing model defaults to configured primary provider model.
- Selecting a model other than the primary requires a configured matching demo access code; otherwise 403.
- Validation errors 400; rate rejection 429; budget rejection 402; provider exception 502.

Success response: `response` (modelUsed, completion, usage.inputTokens/outputTokens/totalTokens), `estimatedCostMicrodollars`, `actualCostMicrodollars`, `settlement` (reservedMicrodollars, actualMicrodollars, adjustmentMicrodollars, spentMicrodollars, remainingMicrodollars, withinBudget), `latencyMs`.

Provider error body: `message`, `releasedReservationMicrodollars`, `settlement`.

### GET `/v1/gateway/status?tenantId=...`
Returns tenantId, tokenBucket, slidingWindow, dailyBudget and circuitBreaker using Java record property names mirrored by Angular interfaces.

### Manual limiter endpoints
- `POST /v1/gateway/check/token-bucket?tenantId=...&cost=1`
- `POST /v1/gateway/check/sliding-window?tenantId=...`

Actuator exposes health, metrics, Prometheus and circuit-breaker endpoints.

## Request lifecycle and invariants

1. Validate body and model access.
2. Increment valid chat request metric.
3. Consume sliding-window request capacity for tenant.
4. Estimate prompt tokens with jtokkit `CL100K_BASE`.
5. Consume token bucket using estimated prompt tokens; bucket is scoped by tenant + model.
6. Resolve model pricing.
7. Reserve estimated input cost plus max output cost (150 tokens) before provider invocation.
8. Call selected provider through retry/circuit-breaker.
9. On provider exception, settle reservation against zero and return 502.
10. On success, price usage using response.modelUsed, record metrics, settle budget and return response.

Token-bucket consumption is not refunded/adjusted to actual usage in the controller. Request rate limit, token limit and budget reservation all precede the provider call.

## Redis structures and Lua semantics

### Sliding-window log
- Key: `ratelimit:sw:{tenantId}`.
- Sorted set: score is Redis server timestamp in milliseconds; member is UUID request ID.
- Lua uses Redis TIME, removes entries at/before the window start, counts ZCARD, admits only below max, adds accepted request and returns `{allowed, current_count, retry_after_seconds}`.
- TTL is window seconds + 1. Defaults: 60 seconds and 100 requests.
- Status reads sorted-set scores using application system time, while mutation uses Redis time; status may be clock-skew-sensitive.

### Token bucket
- Key: `ratelimit:tb:{tenantId}:{model}`; hash fields `tokens` and `last_refill`.
- Lua uses Redis TIME, refills by elapsed seconds × refill rate (capped at capacity), consumes if possible, else computes retry seconds. TTL 3600 seconds.
- Defaults: capacity 10,000, refill 50 tokens/second.
- Service rejects requested cost <= 0 or > capacity.
- Status read estimates refill using application clock, unlike mutation's Redis clock.

### Daily budget reservation
- Key: `quota:daily:{tenantId}:{UTC LocalDate}`; integer microdollar spend.
- Default budget 5,000,000 microdollars ($5); TTL 26 hours.
- Reservation Lua atomically reads spend, rejects if spend + requested exceeds budget, otherwise INCRBYs and refreshes TTL.
- Return: `{allowed, new_or_current_spend}`.

### Daily budget settlement
- Same daily key. Lua calculates adjustment = actual - reserved, then clamps new spend to >= 0, writes spend and refreshes TTL.
- Return: `{reserved, actual, adjustment, new_spent, remaining, within_budget}`.
- Important limitation: settlement has no reservation ID/idempotency ledger and does not verify a prior matching reservation. Repeated settlement applies the adjustment repeatedly. .NET design should introduce reservation identity and settle-once semantics, while deliberately managing API/data compatibility.

Tenant identifiers are concatenated into keys without canonical escaping in Java. Validate identifiers and use deterministic collision-safe encoding in .NET.

## Provider, pricing and resilience

- Provider interface exposes model name and synchronous completion.
- Primary provider is mock or OpenAI based on `llm.provider`.
- OpenAI adapter calls `POST /responses`, sends model/input, reads `output_text` or nested output content, and extracts input_tokens/output_tokens usage.
- If provider usage is absent or zero, local tokenizer count is used as fallback.
- Mock provider deliberately fails randomly at 15%; fallback provider returns deterministic mock completion.
- Pricing (integer microdollars per million tokens): primary-model input 15,000/output 60,000; fallback-model 10,000/40,000; gpt-5-mini 250,000/2,000,000.
- Pricing uses integer division, with positive costs rounded up to at least one microdollar.
- Resilience4j primaryLlm config: 20-call window, 40% failure threshold, 10-second open wait, 5 half-open permitted calls, automatic transition; retry max attempts 2, 200 ms wait.
- Selected-model and primary calls use retry/circuit-breaker annotations; selected model path has fallback method.
- Provider operations are synchronous and do not take cancellation tokens.

## Metrics and observability

Micrometer metrics: `gateway.requests`, `gateway.requests.rejected`, `gateway.llm.calls`, `gateway.llm.failures`, `gateway.tokens`, `gateway.actual.cost.microdollars`, and timer `gateway.llm.latency`.

Actuator exposes health, metrics, Prometheus and circuit-breaker endpoints. Health details are set to always visible; production configuration should restrict sensitive details.

## Existing test coverage

JUnit tests cover token counter, provider/fallback usage, pricing, metric counters and latency; Testcontainers Redis tests cover sliding-window admission/rejection/expiry, token bucket consumption/capacity/retry-after, budget refunds/additional settlement/non-negative clamp; MockMvc controller tests cover happy path, validation, rate rejection, token estimate, failed-provider reservation release and read-only status.

Gaps: explicit concurrency races for all three Redis algorithms; duplicate settlement/replay; cross-model and tenant isolation/key escaping; provider fallback model cost/reservation sufficiency; cancellation/timeouts/retry safety; OpenAI missing-usage/error edge cases; demo code and unsupported model tests; production auth/authorization; verified CI and deployment evidence.

## Java-to-.NET migration mapping

| Java | .NET 10 target |
|---|---|
| Spring Web/controller | ASP.NET Core API/controller |
| Constructor DI | Built-in DI |
| @Value/config properties | Options pattern + validation |
| RedisTemplate/DefaultRedisScript | StackExchange.Redis + Lua ScriptEvaluateAsync |
| Resilience4j | Microsoft.Extensions.Http.Resilience / Polly |
| RestClient | IHttpClientFactory typed client |
| Micrometer | OpenTelemetry/.NET metrics |
| Actuator | ASP.NET Core health checks and telemetry endpoints |
| JUnit 5 | xUnit |
| Testcontainers Java | Testcontainers for .NET |
| jtokkit CL100K_BASE | Verify compatible tokenizer or document/test estimate change |
| Maven/Spring build | .NET solution/SDK |
| Java Docker image | .NET 10 multi-stage container |

## Critical migration risks / decisions

1. Settlement must become idempotent using reservation IDs; current Lua settlement is replay-unsafe.
2. A process crash after reservation but before settlement leaves reserved amount counted until daily-key expiration; production readiness needs reconciliation or expiry policy.
3. Actual response model can differ from requested model due to fallback, while reservation uses requested-model pricing. Ensure reservation covers the possible fallback model or reserve a conservative maximum.
4. Retain integer microdollars and use checked arithmetic/bounds.
5. Use Redis TIME for state transitions; status read should use the same clock or be explicitly approximate.
6. Preserve tokenizer parity with CL100K test vectors or clearly document an estimate change.
7. Keep backend route `/v1/gateway` and frontend `/api/v1/gateway` proxy semantics aligned.
8. Demo access code is not tenant authentication. Production tenant identity must come from authenticated claims, not caller-supplied tenantId.
9. Avoid blind retries of potentially non-idempotent provider POST calls after ambiguous timeouts.
10. Keep optional SQL, Service Bus and Functions out of MVP until durable audit or asynchronous workflow requirements are established.

## Functional parity acceptance criteria

- Preserve routes, request/response names, status codes and error payload semantics unless deliberately versioned.
- Preserve order of request admission, token admission and pre-provider budget reservation.
- Preserve Lua atomicity, Redis server clock, TTLs, tenant/model scoping and default configuration.
- Preserve provider selection/fallback and pricing semantics.
- Preserve Angular model compatibility and dashboard behaviour.
- Verify health/readiness, Docker and local end-to-end path.
- Exercise concurrency against real Redis in integration tests.
- Independently benchmark .NET; never reuse Java benchmark results.

## Scope limitations

This analysis is based on source files read from default branch commit `1bc5b0440d275a466a8a377f35b09f39bdf4dabb`. It does not claim Java tests were executed. Full Angular component visual review and exhaustive HLD/LLD review remain separate tasks. Cloud subscription and credentials have not been verified.