# LLM Gateway

> Multi-tenant rate limiting, cost control, provider resilience & usage observability for LLM traffic.

[![Live Demo](https://img.shields.io/badge/Live%20Demo-Vercel-black?style=flat-square)](https://llm-rate-limiter-gateway.vercel.app/)
[![Backend](https://img.shields.io/badge/Backend-Spring%20Boot-brightgreen?style=flat-square)](https://llm-rate-limiter-gateway.onrender.com/)
[![Redis](https://img.shields.io/badge/State-Redis-red?style=flat-square)](https://redis.io/)
[![Java](https://img.shields.io/badge/Java-17+-orange?style=flat-square)](https://www.java.com/)

## Overview

LLM Gateway is a distributed gateway that sits between clients and LLM providers.

It protects provider traffic using multiple layers of admission control:

- Request-rate limiting with a Redis sliding window
- Token-capacity limiting with a Redis token bucket
- Atomic per-tenant daily cost budgets
- Model-aware usage and pricing
- Provider retry and circuit-breaker protection
- Fallback to a secondary model
- Actual token usage settlement after the provider response
- Runtime health and metrics through Spring Boot Actuator

The project was built to explore the engineering problems that appear when LLM traffic is treated as a shared, multi-tenant infrastructure resource rather than as simple HTTP requests.

---

## Live Demo

**Deployed Link**  
https://llm-rate-limiter-gateway.vercel.app/

The dashboard exposes the gateway's runtime state and allows requests to be sent through the same admission-control and provider-resilience pipeline used by the backend.

The default demo path uses a mock LLM provider. A protected live `gpt-5-mini` path is also available in the deployed environment.

---

## Why This Project?

Traditional request-based rate limiting is not sufficient for LLM workloads.

Two requests can have dramatically different resource consumption:

```text
Request A → 50 tokens
Request B → 5,000 tokens
```

Treating both requests as equivalent does not accurately control model capacity or cost.

LLM Gateway therefore combines:

```text
Request rate
      +
Token capacity
      +
Tenant cost budget
      +
Provider resilience
      +
Actual usage settlement
```

This allows the gateway to control both traffic and LLM resource consumption.

---

## Architecture

![LLM Gateway System Architecture](docs/architecture.svg)

## Request Lifecycle

Every request passes through the gateway before reaching an LLM provider.

```text
1. Receive request
        │
        ▼
2. Identify tenant + model
        │
        ▼
3. Sliding-window admission control
        │
        ├── rejected → HTTP 429
        │
        ▼
4. Token-bucket capacity check
        │
        ├── rejected → HTTP 429
        │
        ▼
5. Estimate request cost
        │
        ▼
6. Atomically reserve tenant budget
        │
        ├── rejected → HTTP 402
        │
        ▼
7. Call selected LLM provider
        │
        ├── retry on failure
        │
        ├── circuit breaker
        │
        └── fallback model
        │
        ▼
8. Read actual token usage
        │
        ▼
9. Calculate actual model cost
        │
        ▼
10. Settle reserved budget
        │
        ▼
11. Return response + usage + cost + latency
```

The important distinction is that **budget reservation happens before the provider call**, while **final cost settlement happens after actual token usage is known**.

---

# Core Engineering Features

## 1. Distributed Token Bucket

The gateway maintains token-bucket state in Redis.

Each tenant/model bucket contains state conceptually equivalent to:

```text
available_tokens
last_refill
```

The bucket refills according to a configured rate and consumes tokens according to the request's estimated token cost.

Current configuration:

```yaml
capacity: 10000
refill-rate-per-sec: 50
```

The bucket operation is implemented using a Redis Lua script so the read/compute/update sequence executes atomically.

### Why Lua?

A naïve implementation could perform:

```text
GET
↓
calculate
↓
SET
```

Two gateway instances could read the same state concurrently and both approve a request.

The Lua script moves the complete state transition into Redis as one atomic operation.

---

## 2. Sliding-Window Rate Limiting

A Redis sorted set is used to maintain request timestamps for each tenant/model.

Current configuration:

```yaml
window-seconds: 60
max-requests: 100
```

The Lua script performs the relevant operations atomically:

```text
remove expired entries
        ↓
count active requests
        ↓
check limit
        ↓
add current request
        ↓
return decision
```

This provides request-rate control independently of token consumption.

---

## 3. Atomic Tenant Budget Reservation

LLM cost is tracked per tenant using microdollar precision.

The gateway first estimates the maximum cost required for a request and atomically reserves that amount.

After the provider responds:

```text
estimated cost
      ↓
reserve budget
      ↓
provider call
      ↓
actual token usage
      ↓
actual cost
      ↓
settlement
```

If the actual cost is lower than the reservation, the difference is released back to the tenant's available budget.

This prevents concurrent requests from overspending the same tenant budget.

---

## 4. Model-Aware Pricing

Pricing is associated with the selected model rather than using one global cost.

The gateway tracks:

```text
input tokens
output tokens
total tokens
estimated cost
actual cost
```

For the live provider, the gateway uses the provider-reported token usage when available.

This is important because the final cost can differ substantially from the initial estimate.

---

## 5. Provider Resilience

Provider calls are protected using Resilience4j.

The current flow is:

```text
Primary provider
      │
      ├── transient failure
      │
      ▼
    Retry
      │
      ├── repeated failures
      │
      ▼
Circuit Breaker
      │
      ▼
Fallback provider/model
```

The circuit breaker prevents repeatedly sending traffic to a failing provider.

Configured behavior includes:

- 20-call sliding window
- 40% failure threshold
- 10-second open-state wait
- 5 permitted half-open calls

The project was tested through:

```text
CLOSED
   ↓
OPEN
   ↓
HALF_OPEN
   ↓
CLOSED / OPEN
```

depending on the results of the half-open calls.

---

## 6. Multi-Tenant Isolation

Gateway state is scoped using tenant and model identifiers.

Conceptually:

```text
tenant-A:primary-model
tenant-A:gpt-5-mini

tenant-B:primary-model
tenant-B:gpt-5-mini
```

This keeps rate-limit and budget state isolated across tenants and models.

The frontend also generates a session-scoped demo tenant automatically so separate browser sessions do not all appear as the same tenant.

---

## 7. Observability

The backend exposes Spring Boot Actuator endpoints for operational visibility.

Metrics include:

- HTTP request metrics
- Gateway request activity
- Provider/circuit-breaker state
- Application health
- Prometheus-compatible metrics

The dashboard exposes request-level information including:

```text
Model
Total tokens
Input tokens
Output tokens
Latency
Estimated cost
Actual cost
Budget reserved
Actual usage
Budget adjustment
```

---

# Rate Limiting Model

The gateway intentionally uses two different rate-limiting mechanisms.

| Mechanism | Controls | Redis structure |
|---|---|---|
| Sliding Window | Request frequency | Sorted Set |
| Token Bucket | Token capacity / burst consumption | Redis keys |
| Tenant Budget | Monetary spend | Redis keys |

They solve different problems.

### Sliding Window

Answers:

> "How many requests has this tenant made recently?"

### Token Bucket

Answers:

> "Does this tenant have enough token capacity available right now?"

### Tenant Budget

Answers:

> "Can this request be financially admitted without exceeding the tenant's daily budget?"

Combining them gives the gateway multiple independent protection layers.

---

# Failure Handling

The gateway exposes meaningful HTTP outcomes for different admission failures.

| Condition | Response |
|---|---|
| Invalid request | `400` |
| Rate limit exceeded | `429` |
| Token capacity exhausted | `429` |
| Daily budget exceeded | `402` |
| Provider operation unavailable | `502` |
| Protected live-model access denied | `403` |

For rate-limit responses, the gateway can return retry information so clients know when another attempt may succeed.

---

# Concurrency

One of the main design goals is correctness under concurrent requests.

Consider:

```text
Tenant budget = $1.00

Request A → reserve $0.70
Request B → reserve $0.70
```

A non-atomic implementation could allow both requests to observe `$1.00` before either update is committed.

The gateway instead performs the budget reservation inside a Redis Lua script.

Conceptually:

```text
read current spend
      +
calculate new spend
      +
validate budget
      +
update spend
      ↓
atomic operation
```

The same atomicity principle is used for the rate-limiting state transitions.

---

# Technology Stack

### Backend

- Java
- Spring Boot
- Spring Web
- Redis
- Redis Lua scripting
- Resilience4j
- Spring Boot Actuator

### Frontend

- Angular
- TypeScript
- RxJS
- SCSS

### Infrastructure

- Redis / Upstash
- Render
- Vercel

### Testing & Performance

- JUnit
- Integration tests
- Concurrent request testing
- Sequential benchmark testing

---

# Performance

A local sequential benchmark was performed against the Spring Boot gateway with Redis and the mock provider.

Results:

| Metric | Result |
|---|---:|
| Requests attempted | 100 |
| Valid successful samples | 99 |
| Average latency | 7.05 ms |
| p50 | 5.74 ms |
| p95 | 15.09 ms |
| p99 | 22.77 ms |
| Maximum | 23.94 ms |
| Sequential throughput | ~141.85 req/s |

This is a **sequential local benchmark**, not a production capacity or formal stress-test result.

The benchmark is therefore intended as an implementation baseline rather than a claim about maximum system throughput.

---

# Project Structure

```text
llm-rate-limiter-gateway/
│
├── backend/
│   └── src/
│       └── main/
│           └── java/
│               └── com/sandesh/ratelimiter/
│                   ├── gateway/
│                   ├── llm/
│                   ├── ratelimit/
│                   ├── budget/
│                   └── config/
│
├── frontend/
│   ├── src/
│   │   └── app/
│   │       ├── core/
│   │       ├── app.ts
│   │       ├── app.html
│   │       └── app.scss
│   └── public/
│       └── gateway-favicon.svg
│
├── Dockerfile
└── README.md
```

---

# Running Locally

## Backend

The backend requires Redis.

Configure the required environment variables:

```text
REDIS_HOST
REDIS_PORT
REDIS_PASSWORD
REDIS_SSL_ENABLED
LLM_PROVIDER
OPENAI_API_KEY
OPENAI_BASE_URL
OPENAI_MODEL
DEMO_ACCESS_CODE
```

The default provider is:

```text
mock
```

so the gateway can be exercised without an external LLM API key.

For the live provider:

```text
LLM_PROVIDER=openai
```

and configure the OpenAI API key through the environment rather than committing it to source control.

---

## Frontend

The Angular frontend communicates with the gateway through:

```text
/api/v1/gateway
```

The production frontend is deployed separately from the Spring Boot backend.

---

# Design Decisions

## Why Redis?

The gateway may run across multiple instances.

In-memory state would therefore produce inconsistent rate limits:

```text
Instance A → local counter
Instance B → different local counter
Instance C → different local counter
```

Redis provides shared state across gateway instances.

---

## Why Redis Lua?

Rate limiting and budget reservation contain read-modify-write operations.

Lua allows the entire state transition to execute atomically inside Redis, avoiding application-level race conditions between gateway instances.

---

## Why both request and token limits?

Request count and token consumption represent different resources.

A tenant could remain below a request-per-minute limit while still sending extremely large prompts.

The two mechanisms therefore provide independent protection.

---

## Why reserve before calling the provider?

Without reservation:

```text
check budget
↓
provider call
↓
charge budget
```

multiple concurrent requests could all pass the initial budget check.

Reservation changes the model to:

```text
reserve budget
↓
provider call
↓
settle actual usage
```

which prevents concurrent requests from spending the same available budget.

---

# Future Improvements

Potential next steps include:

- Distributed idempotency keys for safe client retries
- Redis Cluster-aware key design
- Configurable tenant policies
- Persistent tenant configuration
- Richer production telemetry
- Dedicated load/stress testing
- Authentication and authorization for production tenants
- Provider-specific adaptive routing
- More sophisticated token estimation

These are intentionally outside the current demo scope.

---

# Key Takeaways

This project demonstrates practical distributed-systems concepts around LLM infrastructure:

- Atomic state transitions with Redis Lua
- Distributed rate limiting
- Sliding-window admission control
- Token-bucket capacity management
- Concurrent quota reservation
- Token-based cost accounting
- Provider retry and circuit breaking
- Fallback routing
- Model-aware pricing
- Multi-tenant isolation
- Operational observability

The core design principle is:

```text
Protect the provider
        ↓
Control the tenant
        ↓
Reserve the cost
        ↓
Execute safely
        ↓
Settle actual usage
```

---

## Repository

**GitHub:**  
https://github.com/SandeshKamble45/llm-rate-limiter-gateway

**Live Demo:**  
https://llm-rate-limiter-gateway.vercel.app/