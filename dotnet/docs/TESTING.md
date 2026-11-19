# Testing strategy and execution

## Required local tools

- .NET 10 SDK
- Docker-compatible runtime (Docker Desktop, Docker Engine or equivalent)
- Git

## Commands

From the dotnet directory:

    dotnet restore LlmGateway.sln
    dotnet build LlmGateway.sln --configuration Release
    dotnet test LlmGateway.sln --configuration Release

Redis integration tests use Testcontainers.Redis and run the production Lua scripts against a real Redis container. They require Docker.

## Unit tests currently included

- Model pricing calculates integer microdollar rates.
- Non-positive token counts return zero cost.
- Tiny positive usage is rounded up to one microdollar.
- Checked arithmetic detects overflow.
- Tenant ID validation and key encoding.
- CL100K tokenizer known vector and empty input.

## Redis integration tests currently included

- Sliding window admits the first three requests and rejects the fourth at a configured limit of three.
- Concurrent token bucket admissions cannot exceed configured capacity.
- Concurrent budget reservations cannot overspend the configured tenant budget.
- Duplicate settlement for the same reservation identity does not apply the monetary adjustment twice.
- Tenant budget keys remain isolated.

## CI

GitHub Actions workflow: .github/workflows/dotnet-ci.yml

It performs:
1. .NET 10 restore.
2. Release build.
3. Unit and Redis integration tests.
4. Docker image build.
5. Test result artifact upload.

Azure DevOps pipeline: dotnet/azure-pipelines.yml

It adds an Azure deployment stage that builds and pushes an image and updates Azure Container Apps after the validation stage succeeds.

## Outstanding tests before production parity

- Controller/API contract tests using WebApplicationFactory.
- JWT unauthenticated, valid token, invalid audience and cross-tenant denial tests.
- Provider fallback and circuit-breaker open/recovery tests.
- Retry behaviour for explicit 429, plus no-retry tests for timeout/5xx.
- Provider cancellation and reservation reconciliation.
- Same Idempotency-Key concurrent requests and response replay semantics.
- Model isolation for multiple OpenAI model IDs.
- Sliding-window expiry boundary and retry-after accuracy.
- Redis Cluster deployment validation for hash-tagged multi-key scripts.
- Performance benchmark with reproducible request mix, concurrency, warm/cold Redis and p95/p99 latency.

A test is considered passing only after the CI run reports a successful test step. Do not reuse the Java benchmark result as a .NET benchmark.
