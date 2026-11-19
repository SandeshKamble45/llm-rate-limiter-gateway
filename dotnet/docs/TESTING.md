# Testing strategy and execution

## Required local tools

- .NET 10 SDK
- Node.js 22 and npm
- Docker-compatible runtime (Docker Desktop, Docker Engine or equivalent)
- Git

## Commands

From the repository root:

    cd dotnet
    dotnet restore LlmGateway.sln
    dotnet build LlmGateway.sln --configuration Release
    dotnet test LlmGateway.sln --configuration Release --max-parallel-test-modules 2 --report-trx --results-directory TestResults

The `dotnet/global.json` file selects Microsoft Testing Platform for .NET 10. Both test projects include the TRX reporter extension.

Redis integration tests use Testcontainers.Redis and run the production Lua scripts against a real Redis container. They require Docker.

From the repository root, build the Angular dashboard with:

    cd frontend
    npm ci
    npm run build

## Unit tests currently included

- Model pricing uses integer microdollars.
- Non-positive token counts return zero cost.
- Tiny positive usage is rounded up to one microdollar.
- Checked arithmetic detects overflow.
- Tenant ID validation and key encoding.
- CL100K tokenizer known vector and empty input.

## Redis integration tests currently included

- Sliding window admits the first three requests and rejects the fourth at a configured limit of three.
- Concurrent token bucket admissions cannot exceed configured capacity.
- Concurrent budget reservations cannot overspend the configured tenant budget.
- Duplicate settlement for a reservation identity returns the original settlement without applying the adjustment twice.
- Repeated reservation identity is rejected while in progress.
- A settled reservation cannot be reserved again.
- Tenant budget keys remain isolated.

## CI

GitHub Actions workflow: `.github/workflows/dotnet-ci.yml`

It performs:
1. .NET 10 restore and Release build.
2. Unit and Redis integration tests with Microsoft Testing Platform.
3. Angular dependency restore and production build.
4. Docker image build.
5. Bicep template compilation.
6. Test result artifact upload.

Azure DevOps pipeline: `dotnet/azure-pipelines.yml`

It performs the .NET and Angular validation stages and adds an Azure deployment stage that builds and pushes an image and updates Azure Container Apps after validation succeeds.

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

A test is considered passing only after CI reports that specific test step as successful. Do not reuse the Java benchmark result as a .NET benchmark.
