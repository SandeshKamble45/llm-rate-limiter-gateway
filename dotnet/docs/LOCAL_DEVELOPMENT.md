# Local development

## Prerequisites

- .NET 10 SDK
- Docker Desktop or another Docker-compatible runtime
- Git

## Run with Docker Compose

From the `dotnet/` directory:

```bash
docker compose up --build
```

API endpoints:

- Health: `http://localhost:8080/actuator/health`
- OpenAPI (Development only): `http://localhost:8080/openapi/v1.json`
- Chat: `POST http://localhost:8080/v1/gateway/chat`
- Tenant status: `GET http://localhost:8080/v1/gateway/status?tenantId=tenant-1`

Example request:

```json
{
  "tenantId": "tenant-1",
  "prompt": "Explain how Redis Lua scripts provide atomicity.",
  "model": "primary-model"
}
```

## Run from the SDK

Start Redis separately, then:

```bash
dotnet restore LlmGateway.sln
dotnet build LlmGateway.sln
dotnet test LlmGateway.sln
dotnet run --project src/LlmGateway.Api
```

The default configuration uses the deterministic mock provider. To use the OpenAI provider, configure `Gateway__Provider=openai`, provide `Llm__OpenAiApiKey` through a secret store, and ensure the configured model has an entry in the pricing map.

## Azure Managed Redis local development

Use Azure CLI authentication:

```bash
az login
```

Set `Redis__Endpoint` to the Managed Redis hostname (without port). The application uses Microsoft Entra ID with RESP3 and a 10000/TLS endpoint by default. Add the signed-in developer identity as a Redis user with the least required data permissions in Azure Managed Redis.

Do not commit .env files or actual API keys.
