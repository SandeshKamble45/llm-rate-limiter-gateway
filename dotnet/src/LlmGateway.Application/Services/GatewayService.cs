using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using LlmGateway.Application.Abstractions;
using LlmGateway.Application.Configuration;
using LlmGateway.Domain;
using Microsoft.Extensions.Options;

namespace LlmGateway.Application.Services;

public sealed class GatewayService
{
    private readonly IGatewayStateStore _state;
    private readonly ILlmProvider _provider;
    private readonly ITokenCounter _tokenCounter;
    private readonly IModelPricingService _pricing;
    private readonly GatewayOptions _options;
    private readonly string? _demoAccessCode;

    public GatewayService(
        IGatewayStateStore state,
        ILlmProvider provider,
        ITokenCounter tokenCounter,
        IModelPricingService pricing,
        IOptions<GatewayOptions> options)
    {
        _state = state;
        _provider = provider;
        _tokenCounter = tokenCounter;
        _pricing = pricing;
        _options = options.Value;
        _demoAccessCode = _options.DemoAccessCode;
    }

    public async Task<GatewayResponse> ProcessAsync(
        ChatRequest request,
        string? demoAccessCode,
        string? idempotencyKey,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(request.TenantId))
            throw new GatewayException(400, "tenantId is required");
        if (string.IsNullOrWhiteSpace(request.Prompt))
            throw new GatewayException(400, "prompt is required");
        if (request.Prompt.Length > _options.MaxPromptLength)
            throw new GatewayException(400, $"prompt cannot exceed {_options.MaxPromptLength} characters");

        string tenantId;
        try { tenantId = TenantId.Normalize(request.TenantId); }
        catch (ArgumentException ex) { throw new GatewayException(400, ex.Message); }

        var model = string.IsNullOrWhiteSpace(request.Model) ? _provider.PrimaryModel : request.Model.Trim();
        if (!string.Equals(model, _provider.PrimaryModel, StringComparison.Ordinal) &&
            (string.IsNullOrWhiteSpace(_demoAccessCode) ||
             !FixedTimeEquals(_demoAccessCode, demoAccessCode)))
            throw new GatewayException(403, "Invalid or missing demo access code");

        // Preserve the original admission order: request count, estimated input tokens, then monetary budget.
        var requestLimit = await _state.ConsumeSlidingWindowAsync(tenantId, cancellationToken);
        if (!requestLimit.Allowed)
            throw new GatewayException(429, "Request rate limit exceeded", requestLimit);

        var estimatedInputTokens = Math.Max(1, _tokenCounter.CountTokens(request.Prompt));
        if (estimatedInputTokens > _options.TokenBucketCapacity)
            throw new GatewayException(400, "Estimated prompt tokens exceed the configured token-bucket capacity.");

        var tokenLimit = await _state.ConsumeTokenBucketAsync(tenantId, model, estimatedInputTokens, cancellationToken);
        if (!tokenLimit.Allowed)
            throw new GatewayException(429, "Token capacity exhausted", tokenLimit);

        ModelPricing pricing;
        try { pricing = _pricing.GetPricing(model); }
        catch (ArgumentException ex) { throw new GatewayException(400, ex.Message); }

        var estimatedReservation = checked(
            pricing.CalculateInputCost(estimatedInputTokens) +
            pricing.CalculateOutputCost(_options.MaxOutputTokens));

        var reservationId = BuildReservationId(idempotencyKey);
        var reservation = await _state.ReserveBudgetAsync(
            tenantId, reservationId, estimatedReservation, cancellationToken);
        if (!reservation.Allowed)
            throw new GatewayException(402, "Daily tenant budget exceeded", reservation);

        var timer = Stopwatch.StartNew();
        LlmResponse response;
        try
        {
            response = await _provider.CompleteAsync(model, request.Prompt, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            // A cancelled HTTP exchange can still have reached the provider. Keep the reservation
            // rather than falsely refunding a potentially billable operation. Reconciliation is required.
            throw;
        }
        catch (Exception)
        {
            var settlement = await _state.SettleBudgetAsync(
                tenantId, reservationId, 0, CancellationToken.None);
            throw new GatewayException(
                502,
                "LLM provider unavailable",
                new GatewayErrorResponse("LLM provider unavailable", estimatedReservation, settlement));
        }
        finally
        {
            timer.Stop();
        }

        ModelPricing actualPricing;
        try { actualPricing = _pricing.GetPricing(response.ModelUsed); }
        catch (ArgumentException ex)
        {
            // Do not silently account usage at an unknown price. The reservation remains held.
            throw new GatewayException(502, $"Provider returned an unsupported model: {response.ModelUsed}");
        }

        var actualCost = checked(
            actualPricing.CalculateInputCost(response.Usage.InputTokens) +
            actualPricing.CalculateOutputCost(response.Usage.OutputTokens));

        var finalSettlement = await _state.SettleBudgetAsync(
            tenantId, reservationId, actualCost, cancellationToken);

        return new GatewayResponse(
            response,
            estimatedReservation,
            actualCost,
            finalSettlement,
            timer.ElapsedMilliseconds);
    }

    public async Task<GatewayStatusResponse> GetStatusAsync(string tenantId, CircuitBreakerStatus circuitBreaker, CancellationToken cancellationToken)
    {
        var normalized = TenantId.Normalize(tenantId);
        var primaryModel = _provider.PrimaryModel;
        var tokenStatus = await _state.GetTokenBucketStatusAsync(normalized, primaryModel, cancellationToken);
        var windowStatus = await _state.GetSlidingWindowStatusAsync(normalized, cancellationToken);
        var budgetStatus = await _state.GetBudgetStatusAsync(normalized, cancellationToken);
        return new GatewayStatusResponse(normalized, tokenStatus, windowStatus, budgetStatus, circuitBreaker);
    }

    private static string BuildReservationId(string? idempotencyKey)
    {
        var date = DateTime.UtcNow.ToString("yyyyMMdd", System.Globalization.CultureInfo.InvariantCulture);
        var suffix = string.IsNullOrWhiteSpace(idempotencyKey)
            ? Guid.NewGuid().ToString("N")
            : Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(idempotencyKey.Trim())));
        return $"{date}-{suffix}";
    }

    private static bool FixedTimeEquals(string expected, string? supplied)
    {
        if (string.IsNullOrEmpty(supplied)) return false;
        var left = Encoding.UTF8.GetBytes(expected);
        var right = Encoding.UTF8.GetBytes(supplied);
        return left.Length == right.Length && CryptographicOperations.FixedTimeEquals(left, right);
    }
}
