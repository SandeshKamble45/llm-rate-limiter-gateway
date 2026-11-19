# HTTP API contract

The .NET API keeps the existing gateway route prefix and JSON field names so the Angular dashboard can continue to use the service without a DTO rewrite.

## Base path

The backend route prefix is /v1/gateway.

The Angular development proxy uses /api/v1/gateway and removes the /api prefix before forwarding. A deployed frontend must provide an equivalent reverse proxy or set its API base URL to the backend origin.

## POST /v1/gateway/chat

### Headers

- Content-Type: application/json
- X-Demo-Access-Code: optional demo model gate
- Idempotency-Key: optional stable identifier used to make the budget reservation mutation replay-safe

### Request

    {
      "tenantId": "tenant-1",
      "prompt": "Explain Redis Lua atomicity",
      "model": "primary-model"
    }

Model is optional. If omitted, the API selects the configured primary provider model.

Validation:
- tenantId is required, trimmed, 1–128 characters, and allows letters, digits, period, underscore and hyphen.
- prompt is required and nonblank.
- prompt cannot exceed 10,000 characters.
- the estimated prompt token count cannot exceed the configured token-bucket capacity.
- selecting a non-primary model requires a configured demo access code.

### Successful response

    {
      "response": {
        "modelUsed": "primary-model",
        "completion": "Mock response returned by LLM Gateway.",
        "usage": {
          "inputTokens": 7,
          "outputTokens": 9,
          "totalTokens": 16
        }
      },
      "estimatedCostMicrodollars": 6450,
      "actualCostMicrodollars": 645,
      "settlement": {
        "reservedMicrodollars": 6450,
        "actualMicrodollars": 645,
        "adjustmentMicrodollars": -5805,
        "spentMicrodollars": 645,
        "remainingMicrodollars": 4999355,
        "withinBudget": true
      },
      "latencyMs": 12
    }

Numeric cost values in this example are illustrative; actual amounts are calculated from the model pricing table and provider-reported usage.

### Error contract

| HTTP status | Meaning | Body |
|---|---|---|
| 400 | Invalid body, tenant ID, prompt, model or oversized input | String message |
| 402 | Daily tenant budget could not be reserved | BudgetReservationResult |
| 403 | Missing/invalid demo access code or tenant claim mismatch | String message |
| 429 | Sliding-window or token-bucket rejection | RateLimitResult |
| 502 | Provider unavailable | GatewayErrorResponse |

429 response properties: allowed, remainingTokens, algorithm and retryAfterSeconds. The existing DTO field remainingTokens is retained even when the sliding-window algorithm reports remaining requests.

Provider error response properties: message, releasedReservationMicrodollars and settlement.

The current Idempotency-Key is used to derive a stable budget reservation identity. It does not yet implement an HTTP response replay cache or complete same-key concurrency semantics. Do not describe it as full end-to-end request idempotency.

## GET /v1/gateway/status?tenantId=tenant-1

Returns:
- tenantId
- tokenBucket: availableTokens, capacity, refillRatePerSecond
- slidingWindow: currentRequests, maxRequests, windowSeconds, remainingRequests
- dailyBudget: allowed, spentMicrodollars, remainingMicrodollars
- circuitBreaker: name, state, failureRate, bufferedCalls, failedCalls

Failure rate and buffer counts are a 30-second application observation window. The Polly circuit breaker state is authoritative; the counters are operational telemetry.

## Diagnostic endpoints

- POST /v1/gateway/check/token-bucket?tenantId=tenant-1&cost=1
- POST /v1/gateway/check/sliding-window?tenantId=tenant-1

These endpoints mutate limiter state and are intended for demonstration/diagnostics, not general production client use.

## Health

- GET /actuator/health
- GET /healthz

Both return JSON with a status property. Redis health is included. The endpoints allow anonymous access so Container Apps health probes can call them.

## Authentication

Authentication is disabled by default for local/demo compatibility. When Authentication:Enabled is true:
- ASP.NET Core JWT bearer authentication validates issuer/authority and audience.
- All controller routes require an authenticated principal.
- The configured tenant claim (default tenant_id) must equal the requested tenantId.
- Health probes remain anonymous.

The Angular application has not yet implemented OIDC/MSAL token acquisition; do not enable JWT enforcement until the frontend identity integration is completed.
