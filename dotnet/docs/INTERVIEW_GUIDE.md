# Interview defence guide — LLM Gateway

This guide explains the design decisions in the .NET implementation and distinguishes implemented behaviour from production extensions.

## 1. Why does an LLM gateway need three admission controls?

Request frequency, token consumption and tenant spend are different resource dimensions.

- Sliding window limits request count over a time interval.
- Token bucket limits estimated input-token admission and permits bursts up to capacity.
- Budget reservation prevents concurrent requests from independently spending the same remaining budget.

The current token-bucket business rule consumes estimated prompt tokens only. Completion tokens are measured and priced for monetary settlement, but are not charged back to the token bucket.

## 2. Why Redis Lua?

A naive read-check-write sequence is unsafe under concurrency:

    Request A reads spend = 90
    Request B reads spend = 90
    Budget = 100
    A reserves 10
    B reserves 10

Both requests can believe that capacity is available.

The Lua script performs the check and mutation as one Redis-side atomic operation. All gateway replicas observe the same shared state. This avoids a distributed lock and avoids a multi-command race.

Interview follow-up: What happens if a script has runtime errors after a write? Redis Lua execution is atomic with respect to interleaving, but script writes are not rolled back when a later command errors. Validate arguments and key types before mutation and keep script paths short.

## 3. Why both a sliding window and token bucket?

They are not interchangeable.

- Sliding window enforces exact request count within the configured interval. It stores individual request timestamps, so memory grows with admitted requests inside the window.
- Token bucket stores a balance and last-refill timestamp. It is compact and gives burst-plus-sustained-rate semantics, but it does not enforce an exact rolling request count.

The gateway applies both sequentially because one limits request frequency and the other limits estimated token volume.

## 4. Why reserve budget before calling the provider?

Without reservation, concurrent requests can all read the same remaining budget and make provider calls before any cost is recorded.

The gateway:
1. Estimates input cost and maximum output cost.
2. Atomically reserves the estimated amount.
3. Calls the provider.
4. Prices actual provider usage.
5. Settles the reservation using an atomic adjustment.

Money is represented as integer microdollars, avoiding binary floating-point drift in accounting operations.

## 5. How is settlement replay-safe?

Each reservation has an ID and a Redis reservation hash. The settlement Lua script checks whether that reservation is already settled. If so, it returns the stored settlement result without applying the adjustment again.

The daily budget and reservation keys share a Redis Cluster hash tag so a multi-key script can execute in one slot.

Important distinction: the current Idempotency-Key only identifies the budget reservation. It does not yet cache and replay the complete HTTP response or solve same-key concurrent request semantics.

## 6. What happens if the process terminates after reservation?

A reservation can remain accounted in the daily spend counter until its TTL expires. The current code does not include a durable reconciliation worker. Production designs should persist reservation state durably, add leases/expiry semantics, and reconcile orphaned reservations against provider usage or billing records.

This is a known gap and must not be presented as solved.

## 7. How are provider retries handled?

LLM generation uses a POST operation. A timeout may occur after the provider accepted the request, so blindly retrying can create duplicate inference and billing.

The Polly pipeline retries only an explicit HTTP 429 rejection. Ambiguous timeout and 5xx errors are not retried automatically. A circuit breaker prevents repeated calls while a provider is unhealthy; a fallback model can return a response.

Follow-up: How would you introduce idempotent provider retries? First verify the provider's idempotency-key contract, pass a stable operation key, preserve that key across retries and validate how duplicate responses/usage are represented.

## 8. Why a modular monolith?

Admission, reservation, provider invocation and settlement form one tightly ordered request lifecycle. Splitting them into independently deployed services would introduce more network failure points and distributed transaction semantics before they are required.

The code still has Domain, Application, Infrastructure and API boundaries. Extract a service only when independent scaling, ownership or data isolation justifies the operational cost.

## 9. How does the .NET implementation preserve the Java tokenizer?

The Java source uses jtokkit CL100K_BASE. The .NET code uses SharpToken with cl100k_base and reuses one encoding instance. A known token vector is covered by a unit test. A larger cross-language golden corpus is still required before claiming exhaustive tokenizer parity.

## 10. How does Azure identity work?

- A user-assigned managed identity is attached to the Container App.
- ACR uses AcrPull (or repository reader in ABAC mode) for image pulls.
- Azure Managed Redis adds the identity as a Redis user with a data access policy.
- The application uses DefaultAzureCredential and Microsoft.Azure.StackExchangeRedis for Entra token authentication and refresh.
- The application doesn't store ACR admin credentials or a static Redis password.

ACR pull role and Redis data-plane permission are separate grants.

## 11. How does Container Apps scale?

The starter configuration uses Consumption profile, min replicas zero, max replicas three and HTTP concurrency target 20. This gives cost-efficient scale-to-zero behaviour for a demo. It also causes cold-start latency and has not been benchmarked for the workload.

For assessment practice, switch to Multiple revision mode, deploy a second revision, split traffic and perform rollback.

## 12. What is not yet production complete?

- Full HTTP idempotency with cached response replay.
- Orphaned reservation reconciliation.
- Angular OIDC/MSAL token flow.
- Integration tests for JWT, API contracts and provider fallback.
- Private networking and Premium ACR Private Link lab.
- Load-test methodology and .NET performance baseline.
- Azure DevOps workload identity service connection and verified deployment.
- CI success for the latest branch revision.

## 13. Likely follow-up questions

1. Why is Lua atomic but not equivalent to a database transaction with rollback?
2. What happens during Redis failover while a script is executing?
3. How do you handle a tenant with a very high number of unique models?
4. Why store daily budget in microdollars and not decimal/double?
5. How do you prevent a malicious caller choosing another tenantId?
6. What changes are needed for Redis Cluster and hash-slot locality?
7. How would the system work with multiple providers and model price versions?
8. How can you distinguish provider rejection from an ambiguous network timeout?
9. How do you recover reservations after a process crash?
10. When would you move usage ledger data from Redis to durable SQL storage?

The strongest answers distinguish what this repository currently implements from what a production design would need.
