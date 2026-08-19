# LLM Rate Limiter Gateway

A distributed, token-aware rate limiter and mini AI gateway. Started as a
rate limiter, built to actually solve the problem AI infra teams have in
2026: request-count limits don't reflect real LLM cost — token count and
$ spend do.

## What's already scaffolded here

- **Token bucket** (Redis + Lua, atomic across instances) — `token_bucket.lua` / `TokenBucketRateLimiter`
- **Sliding window log** (second algorithm, for comparison) — `sliding_window.lua` / `SlidingWindowRateLimiter`
- **Circuit breaker + fallback chain** (Resilience4j) — `LlmProviderClient`
- **Per-tenant daily cost budget** — `TenantQuotaService`
- **Gateway endpoint** wiring all three layers — `GatewayController`
- **Metrics** via Actuator + Prometheus, ready for Grafana

## Why this isn't "just a rate limiter" — read before your interview

Be ready to explain, out loud, without notes:

1. **Why the Lua script is necessary.** A naive `GET tokens; if tokens > 0: SET tokens-1`
   from Java is a check-then-act race across concurrent instances. The Lua
   script runs atomically on Redis's single thread — no race is possible,
   full stop. This is the single most-asked follow-up question on any rate
   limiter project.
2. **Token bucket vs sliding window trade-off.** Token bucket = O(1) memory,
   allows controlled bursts, slightly approximate. Sliding window log = exact,
   O(window size) memory per key. Say this without me telling you twice.
3. **Fail-open vs fail-closed.** If Redis is down, do you let all requests
   through (fail-open, protects availability) or block all of them
   (fail-closed, protects the backend)? This repo fails closed right now
   (an exception from `redisTemplate.execute` propagates) — decide
   deliberately and change it if you want fail-open, then explain why.
4. **Why token-based, not request-based.** A 4000-token prompt costs ~400x
   more than a 10-token one. Counting both as "1 request" is the exact gap
   real AI gateways (Zuplo, TrueFoundry, Kong) shipped fixes for this year.

## Day-by-day plan (10-15 days)

**Days 1-2 — Get it running**
- `docker compose up`, hit `/v1/gateway/check/token-bucket` and `/check/sliding-window`
  with curl/Postman, watch values in `redis-cli`.
- Write unit tests for both limiters (allow under capacity, deny over,
  refill over time — use `Thread.sleep` or a fake clock).

**Days 3-4 — Prove distributed correctness**
- Run 2+ instances of the gateway behind nothing (just two ports) against
  the same Redis. Fire concurrent requests with a tool like `hey` or `wrk`
  and confirm the bucket never over-allows. This is your interview story.
- Add a multi-instance test with Testcontainers (already in `pom.xml`).

**Days 5-6 — Wire in the LLM layer**
- Swap the mock in `LlmProviderClient` for a real call (OpenAI or Anthropic,
  cheap model, low volume while testing).
- Replace the char/4 token estimate with a real tokenizer — `jtokkit` is a
  solid Java tiktoken port.
- Trigger the circuit breaker on purpose (kill your API key temporarily,
  or throttle your own network) and watch it fall back. Screenshot this.

**Days 7-8 — Quotas and multi-tenancy**
- Extend `TenantQuotaService` to hierarchical org → team → user budgets.
- Add a `429` response with a clear `Retry-After` and a `402` for budget
  exceeded, matching what real gateways return.

**Days 9-11 — Load test and observability**
- Use k6, Gatling, or `wrk` to simulate a "runaway agent": ramp from 1 req/s
  to 200 req/s in 2 minutes. Capture what happens: 429s kick in, breaker
  trips if you also fail the backend, system stays up.
- Wire Prometheus (already in `docker-compose.yml`) + a Grafana dashboard
  showing requests allowed/denied, breaker state, tokens consumed over time.
  This graph is the single most convincing thing you can put in a README.

**Days 12-13 — Polish for recruiters**
- Architecture diagram (draw.io or excalidraw is fine) showing request flow
  through quota → rate limit → circuit breaker → provider.
- README rewrite: problem statement, design decisions and trade-offs,
  benchmark numbers, what you'd change at 10x scale.
- Deploy somewhere reachable (Render/Railway free tier, or a $5 VPS) so you
  can demo live in an interview instead of describing it.

**Days 14-15 — Buffer**
- Fix whatever broke. Record a 90-second demo video/gif for your resume link.
  A gif of the load test graph is worth more than another bullet point.

## Resume bullet once this is real

> Designed and built a distributed, token-aware rate limiter and AI gateway
> (Java, Spring Boot, Redis, Docker) — atomic multi-instance limiting via
> Redis Lua scripts, circuit-breaker fallback chain (Resilience4j), and
> per-tenant cost budgets; load-tested to N req/s with Prometheus/Grafana
> observability.

Fill in N once you've actually run the load test — a real number beats a
round one.
