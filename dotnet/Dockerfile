# Build from the dotnet/ directory:
# docker build -t llm-gateway-dotnet:local .
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src

COPY Directory.Build.props ./
COPY LlmGateway.sln ./
COPY src/LlmGateway.Domain/LlmGateway.Domain.csproj src/LlmGateway.Domain/
COPY src/LlmGateway.Application/LlmGateway.Application.csproj src/LlmGateway.Application/
COPY src/LlmGateway.Infrastructure/LlmGateway.Infrastructure.csproj src/LlmGateway.Infrastructure/
COPY src/LlmGateway.Api/LlmGateway.Api.csproj src/LlmGateway.Api/
COPY tests/LlmGateway.UnitTests/LlmGateway.UnitTests.csproj tests/LlmGateway.UnitTests/
COPY tests/LlmGateway.IntegrationTests/LlmGateway.IntegrationTests.csproj tests/LlmGateway.IntegrationTests/
RUN dotnet restore LlmGateway.sln

COPY . .
RUN dotnet publish src/LlmGateway.Api/LlmGateway.Api.csproj \
    --configuration Release \
    --output /app/publish \
    --no-restore \
    -p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS final
WORKDIR /app
ENV ASPNETCORE_HTTP_PORTS=8080
EXPOSE 8080
COPY --from=build /app/publish .
USER $APP_UID
ENTRYPOINT ["dotnet", "LlmGateway.Api.dll"]
