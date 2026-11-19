# LLM Gateway — .NET 10 and Azure implementation

This directory contains the .NET migration alongside the original Java implementation. The Java source is intentionally retained until .NET functional parity, CI and deployment are verified.

## What the gateway does

LLM traffic has variable compute and monetary cost. The gateway applies three independent controls before calling a provider:

1. Exact sliding-window request frequency per tenant.
2. Token-bucket admission using estimated prompt tokens per tenant/model.
3. Daily monetary budget reservation in integer microdollars.

After provider execution, the gateway reads actual usage, calculates actual cost and settles the reservation.

## Solution structure

    dotnet/
    ├── LlmGateway.sln
    ├── src/
    │   ├── LlmGateway.Domain/
    │   ├── LlmGateway.Application/
    │   ├── LlmGateway.Infrastructure/
    │   └── LlmGateway.Api/
    ├── tests/
    │   ├── LlmGateway.UnitTests/
    │   └── LlmGateway.IntegrationTests/
    ├── infrastructure/azure/
    ├── docs/
    ├── Dockerfile
    └── docker-compose.yml

## Main implementation decisions

- ASP.NET Core 10 API with Domain, Application, Infrastructure and API boundaries.
- StackExchange.Redis with embedded Lua scripts for atomic state transitions.
- CL100K_BASE tokenizer through SharpToken.
- Integer microdollar pricing and checked arithmetic.
- Reservation records and replay-safe settlement Lua.
- Polly timeout, circuit breaker and a narrowly scoped retry for explicit HTTP 429 rejection only.
- OpenAI Responses API plus deterministic mock and fallback providers.
- OpenTelemetry instruments and Azure Monitor exporter.
- Azure Managed Redis Entra authentication using DefaultAzureCredential and RESP3.
- Azure Container Apps with a user-assigned identity and ACR AcrPull.

## Local setup

Requirements:
- .NET 10 SDK
- Docker-compatible runtime

Run with Docker Compose from this directory:

    docker compose up --build

API:
- Health: http://localhost:8080/actuator/health
- OpenAPI in Development: http://localhost:8080/openapi/v1.json
- Chat: POST http://localhost:8080/v1/gateway/chat
- Status: GET http://localhost:8080/v1/gateway/status?tenantId=tenant-1

Example request:

    {
      "tenantId": "tenant-1",
      "prompt": "Explain Redis Lua atomicity",
      "model": "primary-model"
    }

The default provider is mock. The OpenAI provider requires Llm__OpenAiApiKey and the gpt-5-mini model pricing entry.

## Build and test

From this directory:

    dotnet restore LlmGateway.sln
    dotnet build LlmGateway.sln --configuration Release
    dotnet test LlmGateway.sln --configuration Release

Redis integration tests use Testcontainers and require Docker.

## Azure deployment

Follow:
- docs/DEPLOYMENT.md
- docs/AZURE_SETUP.md
- docs/SECURITY.md
- docs/AZURE_CERTIFICATION_MAPPING.md

Deployment is staged:
1. foundation.bicep creates ACR, identity, role assignment, Log Analytics and Application Insights.
2. Build and push an image.
3. main.bicep creates the Container Apps environment and Container App against that existing image.
4. Add the user-assigned identity as a Redis user in Azure Managed Redis.

## Configuration

Primary local configuration is appsettings.json plus environment variables. Use the .env.example only as a template. Actual .env and *.dev.json parameter files are ignored by Git.

Important settings:
- Gateway__Provider
- Gateway__DailyBudgetMicrodollars
- Gateway__DemoAccessCode
- Llm__OpenAiApiKey
- Redis__Endpoint
- Authentication__Enabled
- Authentication__Authority
- Authentication__Audience

JWT auth defaults off for compatibility with the demo dashboard. Do not enable it until the Angular app has an OIDC token flow and sends the configured tenant claim.

## Implementation status

This is an active migration branch, not yet a completed cutover. The .NET solution, Redis scripts, API, tests, Docker and Azure templates have been added. The original Java code remains the reference implementation.

Do not claim Azure deployment, production readiness, full HTTP idempotency, performance results or completed certification until the corresponding evidence exists. GitHub Actions is the build/test gate; its latest successful run is the source of truth for build and test status.

## Architecture and interview preparation

- HLD: docs/HLD.md
- LLD: docs/LLD.md
- API: docs/API.md
- Testing: docs/TESTING.md
- Interview guide: docs/INTERVIEW_GUIDE.md
