# LLM Gateway — .NET 10 high-level design

## 1. Purpose

The gateway sits between clients and LLM providers. It enforces independent limits for request frequency, estimated token consumption and tenant monetary spend, then orchestrates provider execution, resilience and usage settlement.

## 2. Target deployment architecture

    Angular dashboard / API clients
                |
                v
       ASP.NET Core 10 API
                |
      +---------+----------+
      |                    |
      v                    v
  Application           Provider router
  orchestration         Mock / OpenAI
      |
      v
  Redis state store
      |
      +-- sliding window sorted sets
      +-- token bucket hashes
      +-- daily budget counters
      +-- reservation ledger

Cloud runtime:
- Azure Container Apps Consumption environment.
- Azure Container Registry Standard for image storage.
- User-assigned managed identity for ACR pulls and Redis access.
- Azure Managed Redis for distributed state.
- Log Analytics and Application Insights for logs/telemetry.

## 3. .NET solution boundaries

| Project | Responsibility |
|---|---|
| LlmGateway.Domain | API-independent records, pricing and tenant identifier rules |
| LlmGateway.Application | Use-case orchestration and storage/provider abstractions |
| LlmGateway.Infrastructure | StackExchange.Redis, Lua scripts, OpenAI/mock/fallback providers, Polly |
| LlmGateway.Api | ASP.NET Core endpoints, DI, configuration, JWT option and health probes |
| LlmGateway.UnitTests | Pricing, tenant and tokenizer tests |
| LlmGateway.IntegrationTests | Real Redis Lua integration and concurrency tests |

The Worker, Service Bus and SQL persistence projects are deferred. They are not created as empty placeholders.

## 4. Request lifecycle

1. Validate tenant ID and prompt.
2. Resolve requested model and demo access policy.
3. Atomically consume the tenant's exact sliding-window request capacity.
4. Count prompt tokens with CL100K_BASE.
5. Atomically consume the tenant/model token bucket using estimated input tokens.
6. Resolve model pricing.
7. Reserve estimated input cost plus the configured maximum output cost.
8. Call the selected provider through a Polly timeout/circuit-breaker pipeline.
9. If the OpenAI provider rejects with HTTP 429, one bounded retry is allowed; ambiguous POST timeouts and generic 5xx are not blindly retried.
10. On primary provider failure, execute the configured fallback provider.
11. Calculate actual cost from the model actually returned and provider usage.
12. Settle the budget reservation atomically and return the gateway response.

The original Angular response fields and HTTP status semantics are retained.

## 5. Distributed state

All limiter and budget mutations execute inside Redis Lua scripts. The application does not split a read-check-write sequence across multiple Redis calls.

### Key design

- Sliding window: ratelimit:sw:{tenantKey}
- Token bucket: ratelimit:tb:{tenantKey}:modelKey
- Daily spend: quota:daily:{tenantKey:utcDate}
- Reservation: quota:reservation:{tenantKey:utcDate}:reservationId

The daily key and reservation key share a Redis Cluster hash tag so settlement can update both in one Lua call if Redis Cluster is used.

Tenant identifiers are validated and URL-safe encoded before key construction.

### Sliding window

Redis sorted set:
- score: server timestamp in milliseconds
- member: unique request ID

Lua uses Redis TIME, expires stale entries, checks ZCARD and inserts only accepted requests. Default: 100 requests per 60 seconds.

### Token bucket

Redis hash fields:
- tokens
- last_refill

Lua uses Redis TIME and replenishes based on elapsed seconds. Default capacity: 10,000 input tokens; refill rate: 50 tokens/second. The current business rule consumes estimated input tokens only; output tokens are measured for cost settlement but are not charged back to the token bucket.

### Budget accounting

Daily budget is stored in integer microdollars. Default is 5,000,000 microdollars ($5).

Reservation Lua atomically checks available budget, increments spend and writes a reservation ledger record. Settlement Lua verifies the reservation, applies actual-minus-reserved adjustment and writes settlement results. Repeated settlement for the same reservation ID returns the stored result without applying the adjustment twice.

The reservation ID carries the UTC date so a request crossing midnight settles against the original day's bucket.

## 6. Provider resilience

- Mock provider is deterministic and intended for local/demo use.
- OpenAI provider uses the Responses API and parses provider usage.
- The OpenAI request enforces max_output_tokens=150, matching the amount reserved by the gateway.
- Provider resilience uses Polly Core.
- Failure ratio: 40%.
- Minimum throughput: 20 outcomes in a 30-second sampling period.
- Break duration: 10 seconds.
- One retry is permitted only for an explicit provider 429 rejection.
- Overall provider execution timeout: 40 seconds.
- Fallback provider is deterministic and reports its own model name.

The Java Resilience4j configuration used a count-based 20-call window. The Polly pipeline uses a time-based sampling duration; this is a deliberate implementation difference and must be tested under load.

## 7. Security boundary

The default demo profile is anonymous to preserve the current dashboard. Optional JWT bearer auth validates authority/audience and requires a tenant claim match, but the Angular OIDC/MSAL integration remains outstanding.

Azure:
- ACR admin user disabled.
- ACR pulls use a user-assigned managed identity with AcrPull.
- Azure Managed Redis uses Microsoft Entra access tokens and TLS/RESP3.
- OpenAI API key and demo code are injected through Container Apps secrets.
- Health probes are anonymous.
- The public demo must not be considered a production tenant-isolated service.

## 8. Observability

Business instruments:
- gateway.requests
- gateway.requests.rejected
- gateway.llm.calls
- gateway.llm.failures
- gateway.tokens
- gateway.actual.cost.microdollars
- gateway.llm.latency

OpenTelemetry instruments ASP.NET Core, HttpClient and runtime metrics. In Azure, Azure Monitor OpenTelemetry exports to Application Insights when APPLICATIONINSIGHTS_CONNECTION_STRING is configured.

Do not attach prompts, raw tenant identifiers or credentials as metric labels.

## 9. Non-goals for the initial MVP

- Persistent SQL tenant registry or billing ledger.
- Asynchronous Service Bus request processing.
- Azure Functions background reconciliation.
- Full production OIDC frontend.
- Private networking/Private Link deployment.
- Full HTTP idempotency response replay cache.
- Distributed reservation reconciliation after process termination.
- Multi-region active-active Redis.

These items need explicit requirements and cost review before introduction.
