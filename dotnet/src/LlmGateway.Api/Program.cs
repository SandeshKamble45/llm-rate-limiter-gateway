using LlmGateway.Application.Abstractions;
using LlmGateway.Application.Configuration;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Authorization;
using LlmGateway.Application.Services;
using LlmGateway.Infrastructure;
using LlmGateway.Api;
using Microsoft.Extensions.Options;
using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using System.Text.Json;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;
using StackExchange.Redis;
using Azure.Identity;
using Azure.Monitor.OpenTelemetry.AspNetCore;
using Microsoft.Azure.StackExchangeRedis;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddControllers();
builder.Services.AddOpenApi();

builder.Services.AddSingleton<IValidateOptions<GatewayOptions>, GatewayOptionsValidator>();
builder.Services.AddOptions<GatewayOptions>()
    .BindConfiguration(GatewayOptions.SectionName)
    .ValidateOnStart();
builder.Services.AddOptions<ProviderOptions>()
    .BindConfiguration(ProviderOptions.SectionName)
    .ValidateOnStart();
builder.Services.AddOptions<AuthenticationOptions>()
    .BindConfiguration(AuthenticationOptions.SectionName)
    .ValidateOnStart();

var authOptions = builder.Configuration.GetSection(AuthenticationOptions.SectionName)
    .Get<AuthenticationOptions>() ?? new AuthenticationOptions();
if (authOptions.Enabled)
{
    if (string.IsNullOrWhiteSpace(authOptions.Authority) ||
        string.IsNullOrWhiteSpace(authOptions.Audience))
        throw new InvalidOperationException("Authentication:Authority and Authentication:Audience are required when JWT authentication is enabled.");

    builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
        .AddJwtBearer(options =>
        {
            options.Authority = authOptions.Authority;
            options.Audience = authOptions.Audience;
            options.RequireHttpsMetadata = true;
            options.MapInboundClaims = false;
        });
    builder.Services.AddAuthorization(options =>
    {
        options.FallbackPolicy = new AuthorizationPolicyBuilder()
            .RequireAuthenticatedUser()
            .Build();
    });
}
else
{
    builder.Services.AddAuthorization();
}

IConnectionMultiplexer redisConnection;
var managedRedisEndpoint = builder.Configuration["Redis:Endpoint"];
if (!string.IsNullOrWhiteSpace(managedRedisEndpoint))
{
    // Azure Managed Redis: Microsoft Entra token auth with RESP3 and proactive token refresh.
    var redisOptions = new ConfigurationOptions
    {
        Ssl = true,
        AbortOnConnectFail = false,
        BacklogPolicy = BacklogPolicy.Default,
        Protocol = RedisProtocol.Resp3
    };
    var redisPort = builder.Configuration.GetValue<int?>("Redis:Port") ?? 10000;
    redisOptions.EndPoints.Add(managedRedisEndpoint, redisPort);
    await redisOptions.ConfigureForAzureWithTokenCredentialAsync(new DefaultAzureCredential());
    redisConnection = await ConnectionMultiplexer.ConnectAsync(redisOptions);
}
else
{
    // Local Redis or a non-Entra development cache.
    var redisConnectionString = builder.Configuration.GetConnectionString("Redis")
        ?? builder.Configuration["Redis:ConnectionString"]
        ?? "localhost:6379,abortConnect=false";
    redisConnection = await ConnectionMultiplexer.ConnectAsync(redisConnectionString);
}
builder.Services.AddSingleton(redisConnection);

builder.Services.AddSingleton<IGatewayStateStore, RedisGatewayStateStore>();
builder.Services.AddSingleton<ITokenCounter, Cl100kTokenCounter>();
builder.Services.AddSingleton<IModelPricingService, ModelPricingService>();
builder.Services.AddSingleton<MockLlmProvider>();
builder.Services.AddSingleton<FallbackLlmProvider>();
builder.Services.AddHttpClient<OpenAiLlmProvider>();
builder.Services.AddSingleton<ProviderResiliencePipeline>();
builder.Services.AddSingleton<ILlmProvider, ProviderRouter>();
builder.Services.AddSingleton<GatewayMetrics>();
builder.Services.AddScoped<GatewayService>();

builder.Services.AddHealthChecks()
    .AddCheck<RedisHealthCheck>("redis");

var telemetry = builder.Services.AddOpenTelemetry()
    .ConfigureResource(resource => resource.AddService("llm-gateway-dotnet"))
    .WithMetrics(metrics => metrics.AddMeter(GatewayMetrics.MeterName));

if (!string.IsNullOrWhiteSpace(builder.Configuration["APPLICATIONINSIGHTS_CONNECTION_STRING"]))
{
    telemetry.UseAzureMonitor();
}
else
{
    telemetry.WithTracing(tracing => tracing
        .AddAspNetCoreInstrumentation()
        .AddHttpClientInstrumentation());
    telemetry.WithMetrics(metrics => metrics
        .AddAspNetCoreInstrumentation()
        .AddHttpClientInstrumentation()
        .AddRuntimeInstrumentation());

    if (!string.IsNullOrWhiteSpace(builder.Configuration["OTEL_EXPORTER_OTLP_ENDPOINT"]))
    {
        telemetry.WithTracing(tracing => tracing.AddOtlpExporter());
        telemetry.WithMetrics(metrics => metrics.AddOtlpExporter());
    }
}

var app = builder.Build();

app.UseAuthentication();
app.UseAuthorization();

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

app.MapControllers();
var healthOptions = new HealthCheckOptions
{
    ResponseWriter = async (context, report) =>
    {
        context.Response.ContentType = "application/json";
        await JsonSerializer.SerializeAsync(context.Response.Body,
            new { status = report.Status.ToString() },
            cancellationToken: context.RequestAborted);
    }
};
app.MapHealthChecks("/actuator/health", healthOptions).AllowAnonymous();
app.MapHealthChecks("/healthz", healthOptions).AllowAnonymous();

app.Run();

public partial class Program { }
